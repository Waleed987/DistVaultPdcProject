import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/**
 * DIST-VAULT Chunk Server
 *
 * Responsibilities:
 *  - Stores encrypted chunk data in a local directory
 *  - Registers with the master node on startup
 *  - Sends heartbeat every 30 seconds
 *  - Handles STORE_CHUNK / GET_CHUNK / LIST_CHUNKS from clients
 *  - Verifies chunk integrity with CRC32 checksums
 */
public class ChunkServer {

    static String serverId;
    static int    myPort;
    static String storageDir;

    // Checksum map: chunkHandle -> CRC32 value
    static final ConcurrentHashMap<Long, Long> checksumMap = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {

        if (args.length < 1) {
            System.out.println("Usage: java ChunkServer <serverId>");
            System.out.println("  serverId: integer, e.g. 1  (listens on port 5001)");
            return;
        }

        int id = Integer.parseInt(args[0]);
        serverId   = "CS" + id;
        myPort     = Protocol.CHUNK_BASE_PORT + id - 1;   // CS1=5001, CS2=5002 …
        storageDir = "chunks" + File.separator + serverId;

        // Create storage directory
        new File(storageDir).mkdirs();

        System.out.println("╔══════════════════════════════════════╗");
        System.out.printf ("║  DIST-VAULT  Chunk Server %-12s║%n", serverId);
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("[" + serverId + "] Storage: " + new File(storageDir).getAbsolutePath());
        System.out.println("[" + serverId + "] Listening on port " + myPort);

        // Count existing chunks (resume after restart)
        loadExistingChunks();

        // Register with master
        registerWithMaster();

        // Start heartbeat sender
        Thread hb = new Thread(ChunkServer::heartbeatLoop, "Heartbeat");
        hb.setDaemon(true);
        hb.start();

        // Start listening for client requests
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket ss = new ServerSocket(myPort)) {
            while (true) {
                try {
                    Socket client = ss.accept();
                    pool.submit(new ChunkWorker(client));
                } catch (Exception e) {
                    System.err.println("[" + serverId + "] Accept error: " + e.getMessage());
                }
            }
        }
    }

    // ── Startup ────────────────────────────────────────────────────────────────

    static void loadExistingChunks() {
        File dir = new File(storageDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".chunk")) {
                    try {
                        long handle = Long.parseLong(name.replace(".chunk", ""));
                        long crc = computeFileCRC(f);
                        checksumMap.put(handle, crc);
                    } catch (Exception ignored) {}
                }
            }
        }
        System.out.println("[" + serverId + "] Loaded " + checksumMap.size() + " existing chunks.");
    }

    static void registerWithMaster() {
        try (Socket s = new Socket("localhost", Protocol.MASTER_PORT);
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), "UTF-8"))) {

            out.println(Protocol.REGISTER_CS + Protocol.SEP + serverId + Protocol.SEP + myPort);
            String resp = in.readLine();
            System.out.println("[" + serverId + "] Master registration: " + resp);

        } catch (Exception e) {
            System.err.println("[" + serverId + "] WARNING: Could not register with master: "
                    + e.getMessage());
        }
    }

    // ── Heartbeat ──────────────────────────────────────────────────────────────

    static void heartbeatLoop() {
        while (true) {
            try {
                Thread.sleep(30_000);
                sendHeartbeat();
            } catch (InterruptedException ignored) {}
        }
    }

    static void sendHeartbeat() {
        try (Socket s = new Socket("localhost", Protocol.MASTER_PORT);
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), "UTF-8"))) {

            out.println(Protocol.HEARTBEAT + Protocol.SEP
                    + serverId + Protocol.SEP + checksumMap.size());
            in.readLine(); // consume OK

        } catch (Exception e) {
            System.err.println("[" + serverId + "] Heartbeat failed: " + e.getMessage());
        }
    }

    // ── Chunk helpers ──────────────────────────────────────────────────────────

    static File chunkFile(long handle) {
        return new File(storageDir, handle + ".chunk");
    }

    static long computeFileCRC(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }
}

// ── Chunk Worker Thread ────────────────────────────────────────────────────────

class ChunkWorker implements Runnable {

    private final Socket socket;

    ChunkWorker(Socket socket) { this.socket = socket; }

    @Override
    public void run() {
        try (
            DataInputStream  dataIn  = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            DataOutputStream dataOut = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            BufferedReader   textIn  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"))
        ) {
            // Read command line
            String line = readLine(socket.getInputStream());
            if (line == null || line.isEmpty()) return;

            String[] parts = line.split("\\" + Protocol.SEP, -1);
            String cmd = parts[0];

            switch (cmd) {
                case Protocol.STORE_CHUNK: handleStore(parts, socket); break;
                case Protocol.GET_CHUNK:   handleGet(parts, socket);   break;
                case Protocol.LIST_CHUNKS: handleList(socket);         break;
                default:
                    writeTextLine(socket.getOutputStream(),
                            Protocol.ERROR + Protocol.SEP + "Unknown: " + cmd);
            }
        } catch (Exception e) {
            // ignore disconnects
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // STORE_CHUNK|chunkHandle|dataLength
    // followed by dataLength raw bytes
    private void handleStore(String[] parts, Socket sock) throws Exception {
        if (parts.length < 3) {
            writeTextLine(sock.getOutputStream(), Protocol.ERROR + Protocol.SEP + "Bad STORE_CHUNK");
            return;
        }
        long chunkHandle = Long.parseLong(parts[1]);
        int  dataLen     = Integer.parseInt(parts[2]);

        // Read raw bytes
        byte[] data = new byte[dataLen];
        InputStream is = sock.getInputStream();
        int read = 0;
        while (read < dataLen) {
            int r = is.read(data, read, dataLen - read);
            if (r == -1) break;
            read += r;
        }

        // Write to disk
        File f = ChunkServer.chunkFile(chunkHandle);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data, 0, read);
        }

        // Compute and store checksum
        long crc = 0;
        CRC32 crc32 = new CRC32();
        crc32.update(data, 0, read);
        crc = crc32.getValue();
        ChunkServer.checksumMap.put(chunkHandle, crc);

        System.out.println("[" + ChunkServer.serverId + "] Stored chunk " + chunkHandle
                + " (" + read + " bytes, CRC=" + crc + ")");
        writeTextLine(sock.getOutputStream(), Protocol.OK + Protocol.SEP + crc);
    }

    // GET_CHUNK|chunkHandle
    private void handleGet(String[] parts, Socket sock) throws Exception {
        if (parts.length < 2) {
            writeTextLine(sock.getOutputStream(), Protocol.ERROR + Protocol.SEP + "Bad GET_CHUNK");
            return;
        }
        long chunkHandle = Long.parseLong(parts[1]);
        File f = ChunkServer.chunkFile(chunkHandle);

        if (!f.exists()) {
            writeTextLine(sock.getOutputStream(),
                    Protocol.ERROR + Protocol.SEP + "Chunk not found: " + chunkHandle);
            return;
        }

        // Verify checksum
        long storedCRC   = ChunkServer.checksumMap.getOrDefault(chunkHandle, -1L);
        long computedCRC = ChunkServer.computeFileCRC(f);
        if (storedCRC != -1 && storedCRC != computedCRC) {
            writeTextLine(sock.getOutputStream(),
                    Protocol.ERROR + Protocol.SEP + "Chunk corrupted: " + chunkHandle);
            return;
        }

        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }

        // Send: OK|size\n<raw bytes>
        writeTextLine(sock.getOutputStream(),
                Protocol.OK + Protocol.SEP + data.length + Protocol.SEP + computedCRC);
        sock.getOutputStream().write(data);
        sock.getOutputStream().flush();
        System.out.println("[" + ChunkServer.serverId + "] Sent chunk " + chunkHandle
                + " (" + data.length + " bytes)");
    }

    // LIST_CHUNKS — returns list of stored chunk handles
    private void handleList(Socket sock) throws Exception {
        StringBuilder sb = new StringBuilder(Protocol.OK);
        for (Long h : ChunkServer.checksumMap.keySet()) {
            sb.append(Protocol.SEP).append(h);
        }
        writeTextLine(sock.getOutputStream(), sb.toString());
    }

    // ── I/O Helpers ───────────────────────────────────────────────────────────

    private String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private void writeTextLine(OutputStream os, String line) throws IOException {
        os.write((line + "\n").getBytes("UTF-8"));
        os.flush();
    }
}

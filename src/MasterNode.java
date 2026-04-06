import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// ── Data Models ────────────────────────────────────────────────────────────────

class FileMetaData implements Serializable {
    String fileId;          // unique UUID
    String fileName;
    long   fileSize;
    String patientId;
    String doctorId;
    String recordType;      // e.g. "X-Ray", "Blood Test", "Notes"
    String uploadDate;
    String keywords;
    List<Long> chunkHandles;
    int version;

    FileMetaData(String fileId, String fileName, long fileSize,
                 String patientId, String doctorId, String recordType,
                 String keywords) {
        this.fileId      = fileId;
        this.fileName    = fileName;
        this.fileSize    = fileSize;
        this.patientId   = patientId;
        this.doctorId    = doctorId;
        this.recordType  = recordType;
        this.keywords    = keywords;
        this.uploadDate  = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.chunkHandles = Collections.synchronizedList(new ArrayList<>());
        this.version     = 1;
    }

    /** Pipe-separated summary for protocol transport */
    String toLine() {
        return String.join(Protocol.SEP,
                fileId, fileName, String.valueOf(fileSize),
                patientId, doctorId, recordType,
                uploadDate, keywords, String.valueOf(version),
                String.valueOf(chunkHandles.size()));
    }
}

class ChunkMetaData implements Serializable {
    long   chunkHandle;
    int    version;
    List<String> replicas;   // "host:port" strings
    String primary;
    long   checksum;

    ChunkMetaData(long chunkHandle, List<String> replicas) {
        this.chunkHandle = chunkHandle;
        this.version     = 1;
        this.replicas    = Collections.synchronizedList(new ArrayList<>(replicas));
        this.primary     = replicas.isEmpty() ? "" : replicas.get(0);
    }
}

class ChunkServerInfo implements Serializable {
    String serverId;
    String host;
    int    port;
    long   lastHeartbeat;
    int    chunkCount;
    boolean alive;

    ChunkServerInfo(String serverId, String host, int port) {
        this.serverId      = serverId;
        this.host          = host;
        this.port          = port;
        this.lastHeartbeat = System.currentTimeMillis();
        this.alive         = true;
    }

    String address() { return host + ":" + port; }
}

// ── Master Node ────────────────────────────────────────────────────────────────

/**
 * DIST-VAULT Master Node
 *
 * Responsibilities:
 *  - Maintains namespace (file metadata table)
 *  - Manages chunk allocation and replication
 *  - Authenticates users (RBAC)
 *  - Monitors chunk server health via heartbeat
 *  - Persists state to disk (checkpoints)
 *  - Logs all operations to audit log
 */
public class MasterNode {

    // ── State ──────────────────────────────────────────────────────────────────
    static final ConcurrentHashMap<String, FileMetaData>      fileTable   = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Long,   ChunkMetaData>     chunkTable  = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, ChunkServerInfo>   serverTable = new ConcurrentHashMap<>();

    static final AtomicLong chunkCounter = new AtomicLong(1000);
    static final AtomicLong fileIdCounter = new AtomicLong(1);

    static final int REPLICATION_FACTOR = 3;
    static final long HEARTBEAT_TIMEOUT_MS = 90_000; // 90 seconds

    // Operation log writer
    static PrintWriter opLog;

    // ── Entry Point ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║  DIST-VAULT  Master Node  v1.0       ║");
        System.out.println("╚══════════════════════════════════════╝");

        // Restore state from checkpoint
        loadCheckpoint();

        // Open operation log
        opLog = new PrintWriter(new BufferedWriter(new FileWriter("master-oplog.txt", true)));

        // Start heartbeat monitor thread
        Thread heartbeatMonitor = new Thread(MasterNode::monitorHeartbeats, "HeartbeatMonitor");
        heartbeatMonitor.setDaemon(true);
        heartbeatMonitor.start();

        // Start checkpoint thread (every 5 minutes)
        Thread checkpointThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(300_000);
                    saveCheckpoint();
                } catch (InterruptedException ignored) {}
            }
        }, "CheckpointThread");
        checkpointThread.setDaemon(true);
        checkpointThread.start();

        // Accept clients
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket ss = new ServerSocket(Protocol.MASTER_PORT)) {
            System.out.println("[Master] Listening on port " + Protocol.MASTER_PORT);
            while (true) {
                try {
                    Socket client = ss.accept();
                    pool.submit(new MasterWorker(client));
                } catch (Exception e) {
                    System.err.println("[Master] Accept error: " + e.getMessage());
                }
            }
        }
    }

    // ── Chunk Allocation ───────────────────────────────────────────────────────

    /** Pick 'count' alive chunk servers (round-robin, avoids duplicates). */
    static synchronized List<ChunkServerInfo> pickServers(int count) {
        List<ChunkServerInfo> alive = new ArrayList<>();
        for (ChunkServerInfo cs : serverTable.values()) {
            if (cs.alive) alive.add(cs);
        }
        if (alive.isEmpty()) return alive;
        Collections.shuffle(alive);
        List<ChunkServerInfo> chosen = new ArrayList<>();
        for (int i = 0; i < Math.min(count, alive.size()); i++) {
            chosen.add(alive.get(i));
        }
        return chosen;
    }

    /** Allocate a new chunk handle and assign replica locations. */
    static ChunkMetaData allocateChunk() {
        long handle = chunkCounter.getAndIncrement();
        List<ChunkServerInfo> servers = pickServers(REPLICATION_FACTOR);
        List<String> replicas = new ArrayList<>();
        for (ChunkServerInfo s : servers) replicas.add(s.address());
        ChunkMetaData cm = new ChunkMetaData(handle, replicas);
        chunkTable.put(handle, cm);
        logOp("ALLOC_CHUNK " + handle + " " + replicas);
        return cm;
    }

    // ── Heartbeat Monitor ──────────────────────────────────────────────────────

    static void monitorHeartbeats() {
        while (true) {
            try {
                Thread.sleep(30_000);
                long now = System.currentTimeMillis();
                for (ChunkServerInfo cs : serverTable.values()) {
                    boolean wasAlive = cs.alive;
                    cs.alive = (now - cs.lastHeartbeat) < HEARTBEAT_TIMEOUT_MS;
                    if (wasAlive && !cs.alive) {
                        System.err.println("[Master] WARN: Chunk server "
                                + cs.serverId + " is DEAD (no heartbeat)");
                        AuditLogger.getInstance().log("SYSTEM", "SYSTEM",
                                "SERVER_FAILURE", cs.serverId, "WARNING",
                                "Heartbeat timeout");
                    }
                }
            } catch (InterruptedException ignored) {}
        }
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static void loadCheckpoint() {
        File f = new File("master-checkpoint.dat");
        if (!f.exists()) {
            System.out.println("[Master] No checkpoint found — starting fresh.");
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            Map<String, FileMetaData>    ft = (Map<String, FileMetaData>)   ois.readObject();
            Map<Long,   ChunkMetaData>   ct = (Map<Long,   ChunkMetaData>)  ois.readObject();
            long ctr1 = ois.readLong();
            long ctr2 = ois.readLong();
            fileTable.putAll(ft);
            chunkTable.putAll(ct);
            chunkCounter.set(ctr1);
            fileIdCounter.set(ctr2);
            System.out.println("[Master] Checkpoint restored — "
                    + ft.size() + " files, " + ct.size() + " chunks.");
        } catch (Exception e) {
            System.err.println("[Master] Checkpoint load error: " + e.getMessage());
        }
    }

    static synchronized void saveCheckpoint() {
        try (ObjectOutputStream oos =
                     new ObjectOutputStream(new FileOutputStream("master-checkpoint.dat"))) {
            oos.writeObject(new HashMap<>(fileTable));
            oos.writeObject(new HashMap<>(chunkTable));
            oos.writeLong(chunkCounter.get());
            oos.writeLong(fileIdCounter.get());
            System.out.println("[Master] Checkpoint saved.");
        } catch (IOException e) {
            System.err.println("[Master] Checkpoint save error: " + e.getMessage());
        }
    }

    static synchronized void logOp(String op) {
        if (opLog != null) {
            opLog.println(System.currentTimeMillis() + " " + op);
            opLog.flush();
        }
    }
}

// ── Master Worker Thread ───────────────────────────────────────────────────────

/**
 * Handles a single client or chunk-server connection on the master.
 * Protocol: UTF-8, newline-delimited commands.
 */
class MasterWorker implements Runnable {

    private final Socket socket;
    private String currentUser  = null;
    private String currentRole  = null;

    MasterWorker(Socket socket) { this.socket = socket; }

    @Override
    public void run() {
        String remote = socket.getRemoteSocketAddress().toString();
        try (
            BufferedReader in  = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            PrintWriter    out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
        ) {
            // One command per connection — matches MasterComm.send() open/send/read/close pattern
            String line = in.readLine();
            if (line != null && !line.trim().isEmpty()) {
                line = line.trim();
                String[] parts = line.split("\\" + Protocol.SEP, -1);
                String cmd = parts[0];
                String response = dispatch(cmd, parts, remote);
                out.println(response);
                out.flush();
            }
        } catch (Exception e) {
            // Client disconnected
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }


    private String dispatch(String cmd, String[] parts, String remote) {
        try {
            // Commands that authenticate themselves (no user/pass prefix)
            if (cmd.equals(Protocol.LOGIN))       return handleLogin(parts);
            if (cmd.equals(Protocol.REGISTER_CS)) return handleRegisterCS(parts, remote);
            if (cmd.equals(Protocol.HEARTBEAT))   return handleHeartbeat(parts);
            if (cmd.equals(Protocol.LOGOUT))      return Protocol.OK;

            // All other commands: format is CMD|user|pass|arg1|arg2|...
            // Authenticate inline
            if (parts.length < 3)
                return Protocol.ERROR + Protocol.SEP + "Auth credentials required";
            String user = parts[1];
            String pass = parts[2];
            UserManager.UserRecord rec = UserManager.getInstance().authenticate(user, pass);
            if (rec == null)
                return Protocol.DENIED + Protocol.SEP + "Invalid credentials";
            currentUser = rec.username;
            currentRole = rec.role;

            // Shift args: strip CMD|user|pass → remaining args start at parts[3]
            String[] args = new String[Math.max(1, parts.length - 2)];
            args[0] = cmd;
            if (parts.length > 3)
                System.arraycopy(parts, 3, args, 1, parts.length - 3);

            switch (cmd) {
                case Protocol.CREATE_FILE:      return handleCreateFile(args);
                case Protocol.GET_LOCATIONS:    return handleGetLocations(args);
                case Protocol.LIST_FILES:       return handleListFiles(args);
                case Protocol.SEARCH_FILES:     return handleSearchFiles(args);
                case Protocol.DELETE_FILE:      return handleDeleteFile(args);
                case Protocol.ADD_USER:         return handleAddUser(args);
                case Protocol.DELETE_USER:      return handleDeleteUser(args);
                case Protocol.LIST_USERS:       return handleListUsers();
                case Protocol.GET_AUDIT_LOG:    return handleGetAuditLog();
                case Protocol.GET_SYSTEM_STATS: return handleGetSystemStats();
                default:
                    return Protocol.ERROR + Protocol.SEP + "Unknown command: " + cmd;
            }
        } catch (Exception e) {
            return Protocol.ERROR + Protocol.SEP + e.getMessage();
        }
    }


    // ── Command Handlers ──────────────────────────────────────────────────────

    private String handleLogin(String[] p) {
        if (p.length < 3)
            return Protocol.ERROR + Protocol.SEP + "Usage: LOGIN|username|password";
        String user = p[1], pass = p[2];
        UserManager.UserRecord rec = UserManager.getInstance().authenticate(user, pass);
        if (rec == null) {
            AuditLogger.getInstance().log(user, "?", "LOGIN", "-", "FAILURE", "Bad credentials");
            return Protocol.DENIED + Protocol.SEP + "Invalid credentials";
        }
        currentUser = rec.username;
        currentRole = rec.role;
        AuditLogger.getInstance().log(currentUser, currentRole, "LOGIN", "-", "SUCCESS", "");
        return Protocol.OK + Protocol.SEP + rec.role
                + Protocol.SEP + (rec.patientId != null ? rec.patientId : "");
    }

    private String handleLogout() {
        if (currentUser != null)
            AuditLogger.getInstance().log(currentUser, currentRole, "LOGOUT", "-", "SUCCESS", "");
        currentUser = null;
        currentRole = null;
        return Protocol.OK;
    }

    private String handleRegisterCS(String[] p, String remote) {
        // REGISTER_CS|serverId|port
        if (p.length < 3) return Protocol.ERROR + Protocol.SEP + "Bad REGISTER_CS";
        String serverId = p[1];
        int port = Integer.parseInt(p[2]);
        String host = socket.getInetAddress().getHostAddress();
        ChunkServerInfo cs = new ChunkServerInfo(serverId, host, port);
        MasterNode.serverTable.put(serverId, cs);
        System.out.println("[Master] Chunk server registered: " + serverId
                + " @ " + host + ":" + port);
        MasterNode.logOp("REGISTER_CS " + serverId + " " + host + ":" + port);
        return Protocol.OK;
    }

    private String handleHeartbeat(String[] p) {
        // HEARTBEAT|serverId|chunkCount
        if (p.length < 3) return Protocol.ERROR + Protocol.SEP + "Bad HEARTBEAT";
        String serverId = p[1];
        ChunkServerInfo cs = MasterNode.serverTable.get(serverId);
        if (cs == null) return Protocol.ERROR + Protocol.SEP + "Unknown server";
        cs.lastHeartbeat = System.currentTimeMillis();
        cs.alive = true;
        cs.chunkCount = Integer.parseInt(p[2]);
        return Protocol.OK;
    }

    private String handleCreateFile(String[] p) {
        // CREATE_FILE|filename|filesize|patientId|doctorId|recordType|keywords
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.canWrite(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Write access denied for role: " + currentRole;
        if (p.length < 7) return Protocol.ERROR + Protocol.SEP + "Bad CREATE_FILE args";

        String fileName   = p[1];
        long   fileSize   = Long.parseLong(p[2]);
        String patientId  = p[3];
        String doctorId   = p[4];
        String recordType = p[5];
        String keywords   = p[6];

        String fileId = "F" + MasterNode.fileIdCounter.getAndIncrement();
        FileMetaData fmd = new FileMetaData(fileId, fileName, fileSize,
                patientId, doctorId, recordType, keywords);

        // Calculate number of 64MB chunks
        int numChunks = (int) Math.max(1,
                Math.ceil((double) fileSize / Protocol.CHUNK_SIZE_BYTES));

        List<String> allocatedChunks = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            ChunkMetaData cm = MasterNode.allocateChunk();
            fmd.chunkHandles.add(cm.chunkHandle);
            // Format: chunkHandle:replica1,replica2,replica3
            allocatedChunks.add(cm.chunkHandle + ":" +
                    String.join(",", cm.replicas));
        }

        MasterNode.fileTable.put(fileId, fmd);
        MasterNode.logOp("CREATE_FILE " + fileId + " " + fileName + " chunks=" + numChunks);
        AuditLogger.getInstance().log(currentUser, currentRole,
                "UPLOAD", fileName, "SUCCESS",
                "patientId=" + patientId + " chunks=" + numChunks);

        // Response: OK|fileId|chunk1Handle:rep1,rep2|chunk2Handle:rep1,rep2|...
        StringBuilder sb = new StringBuilder(Protocol.OK + Protocol.SEP + fileId);
        for (String c : allocatedChunks) sb.append(Protocol.SEP).append(c);
        return sb.toString();
    }

    private String handleGetLocations(String[] p) {
        // GET_LOCATIONS|fileId
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (p.length < 2) return Protocol.ERROR + Protocol.SEP + "Bad GET_LOCATIONS";
        String fileId = p[1];
        FileMetaData fmd = MasterNode.fileTable.get(fileId);
        if (fmd == null) return Protocol.ERROR + Protocol.SEP + "File not found: " + fileId;

        // Patient role: can only access own records
        if (Protocol.ROLE_PATIENT.equals(currentRole)) {
            UserManager.UserRecord rec = UserManager.getInstance().getUser(currentUser);
            if (rec == null || !fmd.patientId.equals(rec.patientId))
                return Protocol.DENIED + Protocol.SEP + "Access denied";
        }

        AuditLogger.getInstance().log(currentUser, currentRole,
                "GET_LOCATIONS", fileId, "SUCCESS", "file=" + fmd.fileName);

        StringBuilder sb = new StringBuilder(Protocol.OK + Protocol.SEP + fileId);
        for (long handle : fmd.chunkHandles) {
            ChunkMetaData cm = MasterNode.chunkTable.get(handle);
            if (cm != null) {
                sb.append(Protocol.SEP).append(handle).append(":").append(String.join(",", cm.replicas));
            }
        }
        return sb.toString();
    }

    private String handleListFiles(String[] p) {
        // LIST_FILES or LIST_FILES|patientId
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";

        String filterPatient = null;
        if (Protocol.ROLE_PATIENT.equals(currentRole)) {
            UserManager.UserRecord rec = UserManager.getInstance().getUser(currentUser);
            if (rec != null) filterPatient = rec.patientId;
        } else if (p.length >= 2 && !p[1].isEmpty()) {
            filterPatient = p[1];
        }

        List<String> lines = new ArrayList<>();
        lines.add(Protocol.OK);
        for (FileMetaData fmd : MasterNode.fileTable.values()) {
            if (filterPatient != null && !filterPatient.equals(fmd.patientId)) continue;
            lines.add(fmd.toLine());
        }
        lines.add(Protocol.END);
        return String.join("\n", lines);
    }

    private String handleSearchFiles(String[] p) {
        // SEARCH_FILES|keyword
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (p.length < 2) return Protocol.ERROR + Protocol.SEP + "Bad SEARCH_FILES";
        String keyword = p[1].toLowerCase();

        String filterPatient = null;
        if (Protocol.ROLE_PATIENT.equals(currentRole)) {
            UserManager.UserRecord rec = UserManager.getInstance().getUser(currentUser);
            if (rec != null) filterPatient = rec.patientId;
        }

        List<String> lines = new ArrayList<>();
        lines.add(Protocol.OK);
        for (FileMetaData fmd : MasterNode.fileTable.values()) {
            if (filterPatient != null && !filterPatient.equals(fmd.patientId)) continue;
            if (fmd.fileName.toLowerCase().contains(keyword)
                    || fmd.patientId.toLowerCase().contains(keyword)
                    || fmd.doctorId.toLowerCase().contains(keyword)
                    || fmd.recordType.toLowerCase().contains(keyword)
                    || fmd.keywords.toLowerCase().contains(keyword)) {
                lines.add(fmd.toLine());
            }
        }
        lines.add(Protocol.END);
        return String.join("\n", lines);
    }

    private String handleDeleteFile(String[] p) {
        // DELETE_FILE|fileId
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.isAdmin(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Admin only";
        if (p.length < 2) return Protocol.ERROR + Protocol.SEP + "Bad DELETE_FILE";
        String fileId = p[1];
        FileMetaData fmd = MasterNode.fileTable.remove(fileId);
        if (fmd == null) return Protocol.ERROR + Protocol.SEP + "File not found";
        // Remove chunk metadata (lazy GC — chunk servers still have data until next heartbeat sweep)
        for (long h : fmd.chunkHandles) MasterNode.chunkTable.remove(h);
        AuditLogger.getInstance().log(currentUser, currentRole,
                "DELETE", fmd.fileName, "SUCCESS", "fileId=" + fileId);
        MasterNode.logOp("DELETE_FILE " + fileId);
        return Protocol.OK;
    }

    private String handleAddUser(String[] p) {
        // ADD_USER|username|password|role|patientId
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.isAdmin(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Admin only";
        if (p.length < 5) return Protocol.ERROR + Protocol.SEP + "Bad ADD_USER";
        boolean ok = UserManager.getInstance()
                .addUser(p[1], p[2], p[3], p.length > 4 ? p[4] : null);
        AuditLogger.getInstance().log(currentUser, currentRole,
                "ADD_USER", p[1], ok ? "SUCCESS" : "FAILURE", "role=" + p[3]);
        return ok ? Protocol.OK : Protocol.ERROR + Protocol.SEP + "User already exists";
    }

    private String handleDeleteUser(String[] p) {
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.isAdmin(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Admin only";
        if (p.length < 2) return Protocol.ERROR + Protocol.SEP + "Bad DELETE_USER";
        boolean ok = UserManager.getInstance().removeUser(p[1]);
        AuditLogger.getInstance().log(currentUser, currentRole,
                "DELETE_USER", p[1], ok ? "SUCCESS" : "FAILURE", "");
        return ok ? Protocol.OK : Protocol.ERROR + Protocol.SEP + "User not found";
    }

    private String handleListUsers() {
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.isAdmin(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Admin only";
        List<String> lines = new ArrayList<>();
        lines.add(Protocol.OK);
        for (UserManager.UserRecord u : UserManager.getInstance().listUsers()) {
            lines.add(u.toString());
        }
        lines.add(Protocol.END);
        return String.join("\n", lines);
    }

    private String handleGetAuditLog() {
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        if (!UserManager.isAdmin(currentRole))
            return Protocol.DENIED + Protocol.SEP + "Admin only";
        List<String> lines = new ArrayList<>();
        lines.add(Protocol.OK);
        List<String> log = AuditLogger.getInstance().readAll();
        // Return last 200 entries
        int start = Math.max(0, log.size() - 200);
        lines.addAll(log.subList(start, log.size()));
        lines.add(Protocol.END);
        return String.join("\n", lines);
    }

    private String handleGetSystemStats() {
        if (!requireAuth()) return Protocol.DENIED + Protocol.SEP + "Not logged in";
        int aliveServers = 0, totalChunks = 0;
        for (ChunkServerInfo cs : MasterNode.serverTable.values()) {
            if (cs.alive) { aliveServers++; totalChunks += cs.chunkCount; }
        }
        return Protocol.OK + Protocol.SEP
                + "files=" + MasterNode.fileTable.size() + Protocol.SEP
                + "chunks=" + MasterNode.chunkTable.size() + Protocol.SEP
                + "servers=" + MasterNode.serverTable.size() + Protocol.SEP
                + "aliveServers=" + aliveServers + Protocol.SEP
                + "reportedChunks=" + totalChunks;
    }

    private boolean requireAuth() { return currentUser != null; }
}

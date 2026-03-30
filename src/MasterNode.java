import java.net.*; // Provides networking classes like ServerSocket and Socket
import java.io.*; // Provides input/output stream classes
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

class FileMetaData {
    String fileName;
    long fileSize;
    List<Long> chunkHandles;

    public FileMetaData(String fileName, long fileSize) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.chunkHandles = Collections.synchronizedList(new ArrayList<>());
    }

}

class ChunkMetaData {
    long chunkHandle;
    int version;
    List<String> replicas;
    String primary;

    public ChunkMetaData(long chunkHandle, List<String> replicas, String primary) {
        this.chunkHandle = chunkHandle;
        this.replicas = Collections.synchronizedList(new ArrayList<>());
        this.primary = primary;
    }

}

class ServerInfo {
    String serverAddress;
    int serverPort;

    ServerInfo(String serverAddress, int serverPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
    }
}

// Main class acting as the Master Node (server)
public class MasterNode {

    static int port = 5000;
    static ConcurrentHashMap<String, FileMetaData> fileTable = new ConcurrentHashMap<>();
    static ConcurrentHashMap<Long, ChunkMetaData> chunkTable = new ConcurrentHashMap<>();
    static ConcurrentHashMap<Integer, ServerInfo> serverTable = new ConcurrentHashMap<>();
    List<String> chunkServers = Arrays.asList("S1", "S2", "S3", "S4", "S5");

    static AtomicLong chunkCounter = new AtomicLong(0);
    Random rand = new Random();

    public static void main(String args[]) {
        try {
            ServerSocket ss = new ServerSocket(port);
            System.out.println("Server started at Port  : " + port);
            System.out.println();

            while (true) {
                try (Socket client = ss.accept()) {
                    new Thread(new WorkerHandler(client)).start();

                } catch (Exception e) {
                    e.getStackTrace();
                }
            }

        } catch (Exception e) {
            e.getStackTrace();
        }

    }

    // function that fills chunkTable and fileTable
    // requires filename and fileSize
    // creates file table entry to know if file exists
    // creates chunkTable to know allocated chunks for that file
    public List<ChunkMetaData> createFile(String fileName, int fileSize) {
        FileMetaData fMeta = new FileMetaData(fileName, fileSize); // fills file table
        fileTable.put(fileName, fMeta);

        int chunkSize = 64; // MB / will be used to dic file size to get num of chunks to know how many
                            // replicas to assign
        int numChunks = (int) Math.ceil(fileSize / chunkSize); // finds number of chunks

        List<ChunkMetaData> allocatedChunks = new ArrayList<>(); // create fresh list of allocated chunks for each file

        for (int i = 0; i < numChunks; i++) {
            long chunkHandle = chunkCounter.getAndIncrement();
            List<String> replicas = new ArrayList<>();

            while (replicas.size() < 3) {
                String s = chunkServers.get(rand.nextInt(chunkServers.size()));
                if (!replicas.contains(s)) {
                    replicas.add(s);
                }
            }

            String primary = replicas.get(0);

            ChunkMetaData cMeta = new ChunkMetaData(chunkHandle, replicas, primary);
            chunkTable.put(chunkHandle, cMeta);

        }

        System.out.println("CHUNK TABLE");
        for (Map.Entry<Long, ChunkMetaData> entry : chunkTable.entrySet()) {
            System.out.println(entry.getKey() + " " + entry.getValue());
        }
        return allocatedChunks;

    }
}

class WorkerHandler implements Runnable {

    Socket client;

    WorkerHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        String clientAddress = client.getInetAddress().getHostAddress();
        int clientPort = client.getPort();

        System.out.println("Connected to Client : ");
        System.out.println("Client Address : " + clientAddress + " Client Port : " + clientPort);
        System.out.println();
    }
}

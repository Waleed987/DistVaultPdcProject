import java.net.*; // Provides networking classes like ServerSocket and Socket
import java.io.*; // Provides input/output stream classes
import java.util.*;

public class ChunkServer {
    public static void main(String args[]) {
        if (args.length < 1) {
            System.out.println("Enter Chunk Server ID : (java ChunkServer 1");
            return;
        }

        try (Socket socket = new Socket("localhost", 5000)) {
            System.out.println("Client at Port : " + socket.getPort() + " connected to Server");
        } catch (Exception e) {
            e.getStackTrace();
        }
    }
}

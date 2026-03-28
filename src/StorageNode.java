import java.io.*;
import java.net.*;

public class StorageNode {
    public static void main(String[] args) {
        // Require an ID so we can tell the workers apart in the terminal
        if (args.length < 1) {
            System.out.println("Please provide a Worker ID (e.g., java StorageNode 5001)");
            return;
        }

        String workerId = args[0];

        try (Socket socket = new Socket("127.0.0.1", 5000)) {

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            // Send a test message to the Master
            out.writeUTF("Hello Master, Worker " + workerId + " is connected and ready!");

            // Keep the process alive for 10 seconds so we can see it working
            Thread.sleep(10000);

        } catch (Exception e) {
            System.out.println("Connection failed. Is the Master Node running?");
        }
    }
}
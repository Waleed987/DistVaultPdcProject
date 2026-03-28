import java.net.*; // Provides networking classes like ServerSocket and Socket
import java.io.*; // Provides input/output stream classes

// Main class acting as the Master Node (server)
public class MasterNode {

    public static void main(String args[]) {
        try {
            // Create a ServerSocket that listens on port 5000
            ServerSocket servSocket = new ServerSocket(5000);

            // Print that server is running
            System.out.println("Master Node is online. Listening on port " + 5000 + "...");

            // Infinite loop so server keeps accepting new workers
            while (true) {

                // Wait (block) until a worker connects
                Socket socket = servSocket.accept();

                // Print info about the connected worker (port number)
                System.out.println("New connection detected from " + socket.getPort());

                // Create a new thread for each worker
                // This allows handling multiple workers simultaneously
                new Thread(new WorkerHandler(socket)).start();
            }

        } catch (Exception e) {
            // Catch any error (like port already in use, etc.)
            System.out.println(e);
        }
    }
}

// This class handles communication with a single worker
// Implements Runnable so it can run inside a thread
class WorkerHandler implements Runnable {

    Socket socket; // Socket for communicating with this specific worker

    // Constructor receives the socket from MasterNode
    WorkerHandler(Socket socket) {
        this.socket = socket;
    }

    // This method runs when thread starts
    @Override
    public void run() {
        try {
            // Create input stream to receive data from worker
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Read a message sent by the worker
            // readUTF() reads a string encoded in UTF format
            String message = in.readUTF();

            // Print the received message
            System.out.println("Message from worker: " + message);

        } catch (IOException e) {
            // If worker disconnects or error occurs
            System.out.println("A worker disconnected.");
        }
    }
}
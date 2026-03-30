import java.net.*;
import java.io.*;
import java.util.*; // Needed for List and ArrayList
import java.nio.charset.Charset; // Needed for Charset.defaultCharset()

public class Client {
    public static void main(String args[]) {
        try (Socket socket = new Socket("localhost", 5000)) {
            System.out.println("Client connected with server.");
            File test = new File("C:/Users/pc/Desktop/DistVaultPdcProject/test_patient.txt");

            if (!test.exists()) {
                System.out.println("File not found");
                return;
            }

            // // Call splitFile()
            // List<File> chunks = splitFile(test, 64); // 64 MB per chunk

            // for (File s : chunks) {
            // System.out.println(s);
            // }

            // System.out.println("Created " + chunks.size() + " chunks:");
            // for (File f : chunks) {
            // System.out.println("Absolute File path" + f.getAbsolutePath());
            // System.out.println();
            // }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ====== Your splitFile method goes here ======
    public static List<File> splitFile(File file, int sizeOfFileInMB) throws IOException {
        int counter = 1;
        List<File> files = new ArrayList<File>();
        int sizeOfChunk = 1024 * 1024 * sizeOfFileInMB;
        String eof = System.lineSeparator();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String name = file.getName();
            String line = br.readLine();
            while (line != null) {
                File newFile = new File(file.getParent(), name + "."
                        + String.format("%03d", counter++));
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(newFile))) {
                    int fileSize = 0;
                    while (line != null) {
                        byte[] bytes = (line + eof).getBytes(Charset.defaultCharset());
                        if (fileSize + bytes.length > sizeOfChunk)
                            break;
                        out.write(bytes);
                        fileSize += bytes.length;
                        line = br.readLine();
                    }
                }
                files.add(newFile);
            }
        }
        return files;
    }
}

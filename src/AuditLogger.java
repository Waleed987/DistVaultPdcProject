import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * DIST-VAULT Audit Logger
 * Appends timestamped audit records to audit.log (CSV format).
 * Thread-safe via synchronized writes.
 */
public class AuditLogger {

    private static final String LOG_FILE = "audit.log";
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static AuditLogger instance;
    private final PrintWriter writer;

    private AuditLogger() throws IOException {
        File f = new File(LOG_FILE);
        boolean newFile = !f.exists();
        writer = new PrintWriter(new BufferedWriter(new FileWriter(f, true)));
        if (newFile) {
            writer.println("timestamp,user,role,operation,resource,result,details");
            writer.flush();
        }
    }

    public static synchronized AuditLogger getInstance() {
        if (instance == null) {
            try {
                instance = new AuditLogger();
            } catch (IOException e) {
                throw new RuntimeException("Cannot open audit log: " + e.getMessage());
            }
        }
        return instance;
    }

    /**
     * Log an audit event.
     * @param user      username performing the action
     * @param role      role of the user
     * @param operation e.g. "UPLOAD", "DOWNLOAD", "LOGIN"
     * @param resource  filename or record ID
     * @param result    "SUCCESS" or "FAILURE"
     * @param details   extra context
     */
    public synchronized void log(String user, String role,
                                  String operation, String resource,
                                  String result, String details) {
        String ts = LocalDateTime.now().format(FMT);
        String line = String.join(",",
                escape(ts), escape(user), escape(role),
                escape(operation), escape(resource),
                escape(result), escape(details));
        writer.println(line);
        writer.flush();
    }

    /** Read all audit log entries as a list of strings. */
    public synchronized List<String> readAll() {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(LOG_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            lines.add("Error reading audit log: " + e.getMessage());
        }
        return lines;
    }

    private String escape(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

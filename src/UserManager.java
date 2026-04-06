import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * DIST-VAULT User Manager
 * Manages user accounts with roles and SHA-256 hashed passwords.
 * Persists user data to users.dat.
 *
 * Roles:
 *   DOCTOR  — full read/write access
 *   NURSE   — read-only
 *   ADMIN   — system administration
 *   PATIENT — read own records only
 */
public class UserManager {

    private static final String USER_FILE = "users.dat";
    private static UserManager instance;

    // username -> UserRecord
    private final Map<String, UserRecord> users = new HashMap<>();

    public static class UserRecord implements Serializable {
        public String username;
        public String passwordHash;  // SHA-256 hex
        public String role;          // DOCTOR | NURSE | ADMIN | PATIENT
        public String patientId;     // only relevant for PATIENT role

        public UserRecord(String username, String passwordHash, String role, String patientId) {
            this.username    = username;
            this.passwordHash = passwordHash;
            this.role        = role;
            this.patientId   = patientId;
        }

        @Override
        public String toString() {
            return username + Protocol.SEP + role + Protocol.SEP
                    + (patientId != null ? patientId : "");
        }
    }

    private UserManager() {
        load();
        // Create default admin if no users exist
        if (users.isEmpty()) {
            addUser("admin",    "admin123",  Protocol.ROLE_ADMIN,   null);
            addUser("doctor1",  "doctor123", Protocol.ROLE_DOCTOR,  null);
            addUser("nurse1",   "nurse123",  Protocol.ROLE_NURSE,   null);
            addUser("patient1", "patient123",Protocol.ROLE_PATIENT, "P001");
            save();
        }
    }

    public static synchronized UserManager getInstance() {
        if (instance == null) instance = new UserManager();
        return instance;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns the UserRecord if credentials are valid, null otherwise. */
    public synchronized UserRecord authenticate(String username, String password) {
        UserRecord rec = users.get(username);
        if (rec == null) return null;
        if (!rec.passwordHash.equals(hash(password))) return null;
        return rec;
    }

    /** Add a new user. Returns true on success, false if username exists. */
    public synchronized boolean addUser(String username, String password,
                                         String role, String patientId) {
        if (users.containsKey(username)) return false;
        users.put(username, new UserRecord(username, hash(password), role, patientId));
        save();
        return true;
    }

    /** Remove a user. Returns true if found and removed. */
    public synchronized boolean removeUser(String username) {
        if (users.remove(username) != null) { save(); return true; }
        return false;
    }

    /** Get all user records (copy). */
    public synchronized List<UserRecord> listUsers() {
        return new ArrayList<>(users.values());
    }

    /** Look up a user by username. */
    public synchronized UserRecord getUser(String username) {
        return users.get(username);
    }

    // ── Role permission helpers ────────────────────────────────────────────────

    public static boolean canWrite(String role) {
        return Protocol.ROLE_DOCTOR.equals(role) || Protocol.ROLE_ADMIN.equals(role);
    }

    public static boolean canRead(String role) {
        return true; // all authenticated users can read (patients filtered by patientId separately)
    }

    public static boolean isAdmin(String role) {
        return Protocol.ROLE_ADMIN.equals(role);
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void load() {
        File f = new File(USER_FILE);
        if (!f.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            Map<String, UserRecord> loaded = (Map<String, UserRecord>) ois.readObject();
            users.putAll(loaded);
        } catch (Exception e) {
            System.err.println("[UserManager] Could not load users: " + e.getMessage());
        }
    }

    private void save() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(USER_FILE))) {
            oos.writeObject(new HashMap<>(users));
        } catch (IOException e) {
            System.err.println("[UserManager] Could not save users: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}

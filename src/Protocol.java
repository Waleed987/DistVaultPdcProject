/**
 * DIST-VAULT Protocol Constants
 * Defines the command strings for the text-based TCP protocol.
 */
public class Protocol {

    // ── Master Commands ──────────────────────────────────────────────────────
    public static final String LOGIN            = "LOGIN";
    public static final String LOGOUT           = "LOGOUT";
    public static final String REGISTER_CS      = "REGISTER_CS";       // chunk server → master
    public static final String HEARTBEAT        = "HEARTBEAT";          // chunk server → master
    public static final String CREATE_FILE      = "CREATE_FILE";
    public static final String GET_LOCATIONS    = "GET_LOCATIONS";
    public static final String LIST_FILES       = "LIST_FILES";
    public static final String SEARCH_FILES     = "SEARCH_FILES";
    public static final String DELETE_FILE      = "DELETE_FILE";
    public static final String ADD_USER         = "ADD_USER";
    public static final String DELETE_USER      = "DELETE_USER";
    public static final String LIST_USERS       = "LIST_USERS";
    public static final String GET_AUDIT_LOG    = "GET_AUDIT_LOG";
    public static final String GET_SYSTEM_STATS = "GET_SYSTEM_STATS";
    public static final String APPEND_CHUNK     = "APPEND_CHUNK";

    // ── Chunk Server Commands ─────────────────────────────────────────────────
    public static final String STORE_CHUNK      = "STORE_CHUNK";
    public static final String GET_CHUNK        = "GET_CHUNK";
    public static final String DELETE_CHUNK     = "DELETE_CHUNK";
    public static final String LIST_CHUNKS      = "LIST_CHUNKS";

    // ── Response Codes ────────────────────────────────────────────────────────
    public static final String OK               = "OK";
    public static final String ERROR            = "ERROR";
    public static final String DENIED           = "DENIED";

    // ── Delimiters ────────────────────────────────────────────────────────────
    public static final String SEP              = "|";   // field separator
    public static final String LIST_SEP         = ";";   // list separator
    public static final String END              = "END"; // multi-line response terminator

    // ── Roles ─────────────────────────────────────────────────────────────────
    public static final String ROLE_DOCTOR      = "DOCTOR";
    public static final String ROLE_NURSE       = "NURSE";
    public static final String ROLE_ADMIN       = "ADMIN";
    public static final String ROLE_PATIENT     = "PATIENT";

    // ── Ports ─────────────────────────────────────────────────────────────────
    public static final int MASTER_PORT = 5000;
    public static final int CHUNK_BASE_PORT = 5001;  // server N listens on 5000+N

    // ── Chunk size: 64 MB ─────────────────────────────────────────────────────
    public static final int CHUNK_SIZE_BYTES = 64 * 1024 * 1024;

    private Protocol() {}
}

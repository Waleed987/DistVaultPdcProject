import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

/**
 * DIST-VAULT Client — Swing Desktop GUI
 * Communicates with MasterNode for metadata and ChunkServers for data.
 */
public class Client extends JFrame {

    // ── Session state ──────────────────────────────────────────────────────────
    static String currentUser     = null;
    static String currentRole     = null;
    static String currentPatientId = null;
    static String currentPassword  = null;  // kept for stateless auth

    // ── Colors & Fonts ─────────────────────────────────────────────────────────
    static final Color BG_DARK      = new Color(15, 17, 26);
    static final Color BG_PANEL     = new Color(22, 27, 42);
    static final Color BG_CARD      = new Color(30, 36, 56);
    static final Color ACCENT_BLUE  = new Color(67, 130, 255);
    static final Color ACCENT_TEAL  = new Color(32, 201, 185);
    static final Color ACCENT_RED   = new Color(255, 80, 100);
    static final Color TEXT_PRIMARY = new Color(220, 225, 240);
    static final Color TEXT_MUTED   = new Color(110, 120, 150);
    static final Color BORDER_COLOR = new Color(45, 55, 80);

    static final Font FONT_TITLE   = new Font("Segoe UI", Font.BOLD, 22);
    static final Font FONT_HEADING = new Font("Segoe UI", Font.BOLD, 15);
    static final Font FONT_BODY    = new Font("Segoe UI", Font.PLAIN, 13);
    static final Font FONT_MONO    = new Font("Consolas", Font.PLAIN, 12);
    static final Font FONT_SMALL   = new Font("Segoe UI", Font.PLAIN, 11);

    // ── Main panels ────────────────────────────────────────────────────────────
    private JPanel mainPanel;
    private CardLayout cardLayout;

    // Panels
    private LoginPanel    loginPanel;
    private DashPanel     dashPanel;
    private UploadPanel   uploadPanel;
    private SearchPanel   searchPanel;
    private AdminPanel    adminPanel;

    // ── Entry Point ────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new Client().setVisible(true);
        });
    }

    public Client() {
        super("DIST-VAULT — Distributed Medical Records System");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 720);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);

        cardLayout = new CardLayout();
        mainPanel  = new JPanel(cardLayout);
        mainPanel.setBackground(BG_DARK);

        loginPanel  = new LoginPanel(this);
        dashPanel   = new DashPanel(this);
        uploadPanel = new UploadPanel(this);
        searchPanel = new SearchPanel(this);
        adminPanel  = new AdminPanel(this);

        mainPanel.add(loginPanel,  "LOGIN");
        mainPanel.add(dashPanel,   "DASH");
        mainPanel.add(uploadPanel, "UPLOAD");
        mainPanel.add(searchPanel, "SEARCH");
        mainPanel.add(adminPanel,  "ADMIN");

        add(mainPanel);
        showLogin();
    }

    // ── Navigation ─────────────────────────────────────────────────────────────
    void showLogin()  { cardLayout.show(mainPanel, "LOGIN");  }
    void showDash()   { dashPanel.refresh(); cardLayout.show(mainPanel, "DASH"); }
    void showUpload() { cardLayout.show(mainPanel, "UPLOAD"); }
    void showSearch() { cardLayout.show(mainPanel, "SEARCH"); }
    void showAdmin()  { adminPanel.refresh(); cardLayout.show(mainPanel, "ADMIN"); }

    void afterLogin(String user, String pass, String role, String patientId) {
        currentUser      = user;
        currentPassword  = pass;
        currentRole      = role;
        currentPatientId = patientId;
        showDash();
    }

    void logout() {
        currentUser = null; currentPassword = null;
        currentRole = null; currentPatientId = null;
        showLogin();
    }

    // ── Static UI Helpers ──────────────────────────────────────────────────────
    static JButton makeButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_HEADING);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        btn.addMouseListener(new MouseAdapter() {
            Color orig = bg;
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(orig.brighter());
            }
            public void mouseExited(MouseEvent e) {
                btn.setBackground(orig);
            }
        });
        return btn;
    }

    static JTextField makeField(int cols) {
        JTextField f = new JTextField(cols);
        f.setFont(FONT_BODY);
        f.setBackground(BG_DARK);
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_BLUE);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        return f;
    }

    static JLabel makeLabel(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    static JPanel card(String title) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        if (title != null && !title.isEmpty()) {
            JLabel lbl = makeLabel(title, FONT_HEADING, TEXT_PRIMARY);
            lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
            p.add(lbl, BorderLayout.NORTH);
        }
        return p;
    }

    // ── Master Communication Helper ────────────────────────────────────────────
    static class MasterComm {

        // Commands that do NOT need user|pass injected
        private static final java.util.Set<String> NO_AUTH_CMDS = new java.util.HashSet<>(
            java.util.Arrays.asList(Protocol.LOGIN, Protocol.REGISTER_CS, Protocol.HEARTBEAT));

        /**
         * Send a command to the master.
         * For authenticated commands (everything except LOGIN/REGISTER_CS/HEARTBEAT),
         * automatically injects CMD|user|pass|...rest... so the server can auth statlessly.
         */
        static String send(String command) throws IOException {
            // Inject credentials if needed
            String wire = command;
            if (currentUser != null && currentPassword != null) {
                String cmd = command.contains(Protocol.SEP)
                        ? command.substring(0, command.indexOf(Protocol.SEP))
                        : command;
                if (!NO_AUTH_CMDS.contains(cmd)) {
                    // CMD|user|pass|rest...
                    int firstSep = command.indexOf(Protocol.SEP);
                    String rest = firstSep >= 0 ? command.substring(firstSep + 1) : "";
                    wire = cmd + Protocol.SEP + currentUser + Protocol.SEP + currentPassword
                            + (rest.isEmpty() ? "" : Protocol.SEP + rest);
                }
            }
            try (Socket s = new Socket("localhost", Protocol.MASTER_PORT);
                 PrintWriter out = new PrintWriter(
                         new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
                 BufferedReader in = new BufferedReader(
                         new InputStreamReader(s.getInputStream(), "UTF-8"))) {
                s.setSoTimeout(8000);
                out.println(wire);
                out.flush();
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    if (Protocol.END.equals(line)) break;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
                return sb.toString();
            }
        }
    }

    // ── Chunk Server Communication ─────────────────────────────────────────────
    static class ChunkComm {

        /** Store encrypted bytes on a chunk server. Returns true on success. */
        static boolean storeChunk(String address, long handle, byte[] data) {
            String[] hp = address.split(":");
            try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
                OutputStream os = s.getOutputStream();
                String cmd = Protocol.STORE_CHUNK + Protocol.SEP
                        + handle + Protocol.SEP + data.length + "\n";
                os.write(cmd.getBytes("UTF-8"));
                os.write(data);
                os.flush();
                String resp = readLine(s.getInputStream());
                return resp != null && resp.startsWith(Protocol.OK);
            } catch (Exception e) {
                System.err.println("[Client] StoreChunk failed @ " + address + ": " + e.getMessage());
                return false;
            }
        }

        /** Retrieve encrypted bytes from a chunk server. Returns null on failure. */
        static byte[] getChunk(String address, long handle) {
            String[] hp = address.split(":");
            try (Socket s = new Socket(hp[0], Integer.parseInt(hp[1]))) {
                OutputStream os = s.getOutputStream();
                os.write((Protocol.GET_CHUNK + Protocol.SEP + handle + "\n").getBytes("UTF-8"));
                os.flush();
                String resp = readLine(s.getInputStream());
                if (resp == null || !resp.startsWith(Protocol.OK)) return null;
                String[] rp = resp.split("\\" + Protocol.SEP);
                int size = Integer.parseInt(rp[1]);
                byte[] buf = new byte[size];
                InputStream is = s.getInputStream();
                int read = 0;
                while (read < size) {
                    int r = is.read(buf, read, size - read);
                    if (r == -1) break;
                    read += r;
                }
                return buf;
            } catch (Exception e) {
                System.err.println("[Client] GetChunk failed @ " + address + ": " + e.getMessage());
                return null;
            }
        }

        static String readLine(InputStream is) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            return sb.toString();
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LOGIN PANEL
// ═══════════════════════════════════════════════════════════════════════════════

class LoginPanel extends JPanel {
    private final Client app;
    private JTextField userField;
    private JPasswordField passField;
    private JLabel statusLabel;

    LoginPanel(Client app) {
        this.app = app;
        setBackground(Client.BG_DARK);
        setLayout(new GridBagLayout());
        buildUI();
    }

    private void buildUI() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(Client.BG_PANEL);
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Client.BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(40, 50, 40, 50)));

        // Logo / Title
        JLabel logo = Client.makeLabel("🏥 DIST-VAULT", new Font("Segoe UI", Font.BOLD, 28), Client.ACCENT_BLUE);
        logo.setAlignmentX(CENTER_ALIGNMENT);
        JLabel sub = Client.makeLabel("Distributed Medical Records System",
                Client.FONT_SMALL, Client.TEXT_MUTED);
        sub.setAlignmentX(CENTER_ALIGNMENT);

        userField = Client.makeField(20);
        userField.setMaximumSize(new Dimension(320, 40));
        passField = new JPasswordField(20);
        passField.setFont(Client.FONT_BODY);
        passField.setBackground(Client.BG_DARK);
        passField.setForeground(Client.TEXT_PRIMARY);
        passField.setCaretColor(Client.ACCENT_BLUE);
        passField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Client.BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        passField.setMaximumSize(new Dimension(320, 40));

        statusLabel = Client.makeLabel(" ", Client.FONT_SMALL, Client.ACCENT_RED);
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);

        JButton loginBtn = Client.makeButton("Sign In", Client.ACCENT_BLUE);
        loginBtn.setAlignmentX(CENTER_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(320, 42));
        loginBtn.addActionListener(e -> doLogin());
        passField.addActionListener(e -> doLogin());

        // Hint panel
        JPanel hint = new JPanel(new GridLayout(4, 2, 4, 2));
        hint.setBackground(Client.BG_DARK);
        hint.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Client.BORDER_COLOR),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        String[][] defaults = {{"admin/admin123","ADMIN"},{"doctor1/doctor123","DOCTOR"},
                               {"nurse1/nurse123","NURSE"},{"patient1/patient123","PATIENT"}};
        for (String[] d : defaults) {
            hint.add(Client.makeLabel(d[0], Client.FONT_MONO, Client.ACCENT_TEAL));
            hint.add(Client.makeLabel(d[1], Client.FONT_SMALL, Client.TEXT_MUTED));
        }

        box.add(logo); box.add(Box.createVerticalStrut(4));
        box.add(sub);  box.add(Box.createVerticalStrut(30));
        box.add(Client.makeLabel("Username", Client.FONT_SMALL, Client.TEXT_MUTED));
        box.add(Box.createVerticalStrut(4));
        box.add(userField); box.add(Box.createVerticalStrut(14));
        box.add(Client.makeLabel("Password", Client.FONT_SMALL, Client.TEXT_MUTED));
        box.add(Box.createVerticalStrut(4));
        box.add(passField); box.add(Box.createVerticalStrut(18));
        box.add(loginBtn);  box.add(Box.createVerticalStrut(10));
        box.add(statusLabel); box.add(Box.createVerticalStrut(20));
        box.add(Client.makeLabel("Default Accounts:", Client.FONT_SMALL, Client.TEXT_MUTED));
        box.add(Box.createVerticalStrut(6));
        box.add(hint);

        add(box);
    }

    private void doLogin() {
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            statusLabel.setText("Please enter username and password.");
            return;
        }
        statusLabel.setText("Connecting...");
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(
                        Protocol.LOGIN + Protocol.SEP + user + Protocol.SEP + pass);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp == null) { statusLabel.setText("Cannot connect to master server."); return; }
                    String[] parts = resp.split("\\" + Protocol.SEP, -1);
                    if (parts[0].equals(Protocol.OK)) {
                        String role = parts.length > 1 ? parts[1] : "?";
                        String pid  = parts.length > 2 ? parts[2] : "";
                        app.afterLogin(user, pass, role, pid);
                        userField.setText(""); passField.setText("");
                        statusLabel.setText(" ");
                    } else {
                        statusLabel.setText(parts.length > 1 ? parts[1] : "Login failed.");
                    }
                } catch (Exception e) { statusLabel.setText("Error: " + e.getMessage()); }
            }
        };
        worker.execute();
    }
}

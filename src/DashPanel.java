import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * Dashboard Panel — shown after login.
 * Sidebar navigation + stat cards + welcome info.
 */
class DashPanel extends JPanel {

    private final Client app;
    private JLabel userLabel, roleLabel;
    private JLabel filesLabel, chunksLabel, serversLabel, aliveLabel;

    DashPanel(Client app) {
        this.app = app;
        setBackground(Client.BG_DARK);
        setLayout(new BorderLayout());
        buildUI();
    }

    private void buildUI() {
        add(buildSidebar(), BorderLayout.WEST);
        add(buildContent(), BorderLayout.CENTER);
    }

    JPanel buildSidebar() {
        JPanel side = new JPanel();
        side.setBackground(Client.BG_PANEL);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(210, 0));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Client.BORDER_COLOR));

        JLabel logo = Client.makeLabel("🏥 DIST-VAULT", new Font("Segoe UI", Font.BOLD, 17), Client.ACCENT_BLUE);
        logo.setAlignmentX(LEFT_ALIGNMENT);
        logo.setBorder(BorderFactory.createEmptyBorder(24, 18, 6, 18));

        JLabel ver = Client.makeLabel("v1.0 — Medical Records", Client.FONT_SMALL, Client.TEXT_MUTED);
        ver.setAlignmentX(LEFT_ALIGNMENT);
        ver.setBorder(BorderFactory.createEmptyBorder(0, 18, 24, 18));

        side.add(logo);
        side.add(ver);
        side.add(makeDivider());
        side.add(navBtn("🏠  Dashboard",  e -> app.showDash()));
        side.add(navBtn("📤  Upload Record", e -> app.showUpload()));
        side.add(navBtn("🔍  Search Records", e -> app.showSearch()));
        if (Protocol.ROLE_ADMIN.equals(Client.currentRole)) {
            side.add(navBtn("⚙️  Admin Panel", e -> app.showAdmin()));
        }
        side.add(Box.createVerticalGlue());
        side.add(makeDivider());

        userLabel = Client.makeLabel("", Client.FONT_BODY, Client.TEXT_PRIMARY);
        roleLabel = Client.makeLabel("", Client.FONT_SMALL, Client.TEXT_MUTED);
        userLabel.setAlignmentX(LEFT_ALIGNMENT);
        roleLabel.setAlignmentX(LEFT_ALIGNMENT);
        userLabel.setBorder(BorderFactory.createEmptyBorder(12, 18, 2, 18));
        roleLabel.setBorder(BorderFactory.createEmptyBorder(0, 18, 4, 18));

        JButton logoutBtn = Client.makeButton("Sign Out", new Color(60, 40, 55));
        logoutBtn.setAlignmentX(LEFT_ALIGNMENT);
        logoutBtn.setMaximumSize(new Dimension(200, 36));
        logoutBtn.setFont(Client.FONT_SMALL);
        logoutBtn.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));
        logoutBtn.addActionListener(e -> app.logout());

        side.add(userLabel);
        side.add(roleLabel);
        side.add(logoutBtn);
        side.add(Box.createVerticalStrut(16));
        return side;
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Client.BG_DARK);

        // Top bar
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Client.BG_PANEL);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Client.BORDER_COLOR),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));
        JLabel title = Client.makeLabel("Dashboard", Client.FONT_TITLE, Client.TEXT_PRIMARY);
        JButton refreshBtn = Client.makeButton("↻ Refresh", Client.BG_CARD);
        refreshBtn.setFont(Client.FONT_SMALL);
        refreshBtn.addActionListener(e -> refresh());
        top.add(title, BorderLayout.WEST);
        top.add(refreshBtn, BorderLayout.EAST);
        content.add(top, BorderLayout.NORTH);

        // Stat cards
        JPanel cards = new JPanel(new GridLayout(1, 4, 16, 0));
        cards.setBackground(Client.BG_DARK);
        cards.setBorder(BorderFactory.createEmptyBorder(24, 24, 16, 24));

        filesLabel   = statValue("0");
        chunksLabel  = statValue("0");
        serversLabel = statValue("0");
        aliveLabel   = statValue("0");

        cards.add(statCard("📁 Total Files",   filesLabel,   Client.ACCENT_BLUE));
        cards.add(statCard("🧩 Chunks",        chunksLabel,  Client.ACCENT_TEAL));
        cards.add(statCard("🖥  Servers",      serversLabel, new Color(180, 120, 255)));
        cards.add(statCard("💚 Alive Servers", aliveLabel,   new Color(80, 200, 120)));
        content.add(cards, BorderLayout.NORTH);

        // Info panel
        JPanel info = new JPanel(new BorderLayout());
        info.setBackground(Client.BG_DARK);
        info.setBorder(BorderFactory.createEmptyBorder(0, 24, 24, 24));

        JPanel about = Client.card("About DIST-VAULT");
        JTextArea ta = new JTextArea(
            "DIST-VAULT is a GFS-inspired distributed medical records system.\n\n" +
            "• Medical records are fragmented into 64 MB chunks\n" +
            "• Each chunk is encrypted with AES-256 (patient-specific key)\n" +
            "• Chunks are replicated across 3 chunk servers for fault tolerance\n" +
            "• Role-based access control: Doctor, Nurse, Admin, Patient\n" +
            "• All operations are recorded in the audit log\n\n" +
            "Quick Start:\n" +
            "  1. Click 'Upload Record' to add a new medical record\n" +
            "  2. Click 'Search Records' to find and download existing records\n" +
            "  3. Admins can manage users and view audit logs in Admin Panel"
        );
        ta.setFont(Client.FONT_BODY);
        ta.setForeground(Client.TEXT_MUTED);
        ta.setBackground(Client.BG_CARD);
        ta.setEditable(false);
        ta.setWrapStyleWord(true);
        ta.setLineWrap(true);
        ta.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        about.add(ta, BorderLayout.CENTER);
        info.add(about, BorderLayout.CENTER);
        content.add(info, BorderLayout.CENTER);
        return content;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JPanel statCard(String title, JLabel valueLabel, Color accent) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Client.BG_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Client.BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        JLabel t = Client.makeLabel(title, Client.FONT_SMALL, Client.TEXT_MUTED);
        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 32));
        valueLabel.setForeground(accent);
        p.add(t, BorderLayout.NORTH);
        p.add(valueLabel, BorderLayout.CENTER);
        return p;
    }

    private JLabel statValue(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 32));
        return l;
    }

    private JPanel makeDivider() {
        JPanel d = new JPanel();
        d.setBackground(Client.BORDER_COLOR);
        d.setMaximumSize(new Dimension(10000, 1));
        d.setPreferredSize(new Dimension(0, 1));
        return d;
    }

    private JButton navBtn(String text, ActionListener al) {
        JButton b = new JButton("  " + text);
        b.setFont(Client.FONT_BODY);
        b.setForeground(Client.TEXT_PRIMARY);
        b.setBackground(Client.BG_PANEL);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMaximumSize(new Dimension(10000, 42));
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.addActionListener(al);
        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { b.setBackground(Client.BG_CARD); }
            public void mouseExited(MouseEvent e)  { b.setBackground(Client.BG_PANEL); }
        });
        return b;
    }

    void refresh() {
        if (Client.currentUser == null) return;
        userLabel.setText("👤 " + Client.currentUser);
        roleLabel.setText(Client.currentRole);
        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(Protocol.GET_SYSTEM_STATS);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp == null || !resp.startsWith(Protocol.OK)) return;
                    String[] parts = resp.split("\\" + Protocol.SEP);
                    for (String part : parts) {
                        if (part.startsWith("files="))   filesLabel.setText(part.substring(6));
                        if (part.startsWith("chunks="))  chunksLabel.setText(part.substring(7));
                        if (part.startsWith("servers=")) serversLabel.setText(part.substring(8));
                        if (part.startsWith("aliveServers=")) aliveLabel.setText(part.substring(13));
                    }
                } catch (Exception ignored) {}
            }
        };
        w.execute();
    }
}

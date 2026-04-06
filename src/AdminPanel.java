import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Admin Panel — user management, system stats, audit log viewer.
 * Only visible to users with ADMIN role.
 */
class AdminPanel extends JPanel {

    private final Client app;
    private JTabbedPane tabs;

    // Users tab
    private DefaultTableModel usersModel;
    private JTable usersTable;

    // Audit tab
    private JTextArea auditArea;

    // Stats tab
    private JTextArea statsArea;

    AdminPanel(Client app) {
        this.app = app;
        setBackground(Client.BG_DARK);
        setLayout(new BorderLayout());
        buildUI();
    }

    private void buildUI() {
        // Top bar
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Client.BG_PANEL);
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Client.BORDER_COLOR),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));
        JButton back = Client.makeButton("← Back", Client.BG_CARD);
        back.setFont(Client.FONT_SMALL);
        back.addActionListener(e -> app.showDash());
        top.add(Client.makeLabel("⚙️  Admin Panel", Client.FONT_TITLE, Client.TEXT_PRIMARY), BorderLayout.WEST);
        top.add(back, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // Tabs
        tabs = new JTabbedPane();
        tabs.setFont(Client.FONT_BODY);
        tabs.setBackground(Client.BG_DARK);
        tabs.setForeground(Client.TEXT_PRIMARY);
        tabs.addTab("👥  Users",    buildUsersTab());
        tabs.addTab("📋  Audit Log", buildAuditTab());
        tabs.addTab("📊  System Stats", buildStatsTab());
        add(tabs, BorderLayout.CENTER);
    }

    // ── Users Tab ─────────────────────────────────────────────────────────────

    private JPanel buildUsersTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Client.BG_DARK);
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        String[] cols = {"Username", "Role", "Patient ID"};
        usersModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        usersTable = new JTable(usersModel);
        styleTable(usersTable);

        JScrollPane scroll = new JScrollPane(usersTable);
        scroll.getViewport().setBackground(Client.BG_PANEL);
        scroll.setBorder(BorderFactory.createLineBorder(Client.BORDER_COLOR));
        p.add(scroll, BorderLayout.CENTER);

        // Buttons
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        btnBar.setBackground(Client.BG_DARK);

        JButton addBtn = Client.makeButton("➕ Add User", Client.ACCENT_BLUE);
        addBtn.addActionListener(e -> showAddUserDialog());

        JButton delBtn = Client.makeButton("🗑 Delete User", Client.ACCENT_RED);
        delBtn.addActionListener(e -> deleteSelectedUser());

        JButton refreshBtn = Client.makeButton("↻ Refresh", Client.BG_CARD);
        refreshBtn.addActionListener(e -> loadUsers());

        btnBar.add(addBtn); btnBar.add(delBtn); btnBar.add(refreshBtn);
        p.add(btnBar, BorderLayout.SOUTH);
        return p;
    }

    private void loadUsers() {
        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(Protocol.LIST_USERS);
            }
            protected void done() {
                try {
                    String resp = get();
                    usersModel.setRowCount(0);
                    if (resp == null || !resp.contains("\n")) return;
                    for (String line : resp.split("\n")) {
                        if (line.equals(Protocol.OK) || line.isEmpty()) continue;
                        String[] parts = line.split("\\" + Protocol.SEP, -1);
                        if (parts.length >= 2) {
                            usersModel.addRow(new Object[]{
                                parts[0], parts[1],
                                parts.length > 2 ? parts[2] : ""
                            });
                        }
                    }
                } catch (Exception e) { showErr("Load users: " + e.getMessage()); }
            }
        };
        w.execute();
    }

    private void showAddUserDialog() {
        JPanel dlg = new JPanel(new GridLayout(5, 2, 8, 8));
        dlg.setBackground(Client.BG_PANEL);
        JTextField uField  = Client.makeField(14);
        JPasswordField pField = new JPasswordField(14);
        pField.setBackground(Client.BG_DARK);
        pField.setForeground(Client.TEXT_PRIMARY);
        String[] roles = {Protocol.ROLE_DOCTOR, Protocol.ROLE_NURSE,
                          Protocol.ROLE_ADMIN, Protocol.ROLE_PATIENT};
        JComboBox<String> roleBox = new JComboBox<>(roles);
        roleBox.setBackground(Client.BG_DARK);
        roleBox.setForeground(Client.TEXT_PRIMARY);
        JTextField pidField = Client.makeField(14);

        dlg.add(label("Username:")); dlg.add(uField);
        dlg.add(label("Password:")); dlg.add(pField);
        dlg.add(label("Role:"));     dlg.add(roleBox);
        dlg.add(label("Patient ID (if PATIENT role):")); dlg.add(pidField);

        int res = JOptionPane.showConfirmDialog(this, dlg, "Add New User",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;

        String user = uField.getText().trim();
        String pass = new String(pField.getPassword());
        String role = (String) roleBox.getSelectedItem();
        String pid  = pidField.getText().trim();
        if (user.isEmpty() || pass.isEmpty()) { showErr("Username and password required."); return; }

        String cmd = String.join(Protocol.SEP,
                Protocol.ADD_USER, user, pass, role, pid);

        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(cmd);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp != null && resp.startsWith(Protocol.OK)) {
                        JOptionPane.showMessageDialog(AdminPanel.this,
                                "User '" + user + "' created.", "Success",
                                JOptionPane.INFORMATION_MESSAGE);
                        loadUsers();
                    } else {
                        showErr("Add user failed: " + resp);
                    }
                } catch (Exception e) { showErr(e.getMessage()); }
            }
        };
        w.execute();
    }

    private void deleteSelectedUser() {
        int row = usersTable.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(this, "Select a user first."); return; }
        String uname = (String) usersModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete user '" + uname + "'?", "Confirm Delete",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(Protocol.DELETE_USER + Protocol.SEP + uname);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp != null && resp.startsWith(Protocol.OK)) {
                        loadUsers();
                    } else {
                        showErr("Delete failed: " + resp);
                    }
                } catch (Exception e) { showErr(e.getMessage()); }
            }
        };
        w.execute();
    }

    // ── Audit Tab ─────────────────────────────────────────────────────────────

    private JPanel buildAuditTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Client.BG_DARK);
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        auditArea = new JTextArea();
        auditArea.setFont(Client.FONT_MONO);
        auditArea.setForeground(Client.ACCENT_TEAL);
        auditArea.setBackground(new Color(10, 14, 22));
        auditArea.setEditable(false);
        auditArea.setCaretColor(Client.ACCENT_TEAL);

        JScrollPane scroll = new JScrollPane(auditArea);
        scroll.setBorder(BorderFactory.createLineBorder(Client.BORDER_COLOR));
        p.add(scroll, BorderLayout.CENTER);

        JButton refresh = Client.makeButton("↻ Refresh Audit Log", Client.ACCENT_BLUE);
        refresh.addActionListener(e -> loadAuditLog());
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bot.setBackground(Client.BG_DARK);
        bot.add(refresh);
        p.add(bot, BorderLayout.SOUTH);
        return p;
    }

    private void loadAuditLog() {
        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(Protocol.GET_AUDIT_LOG);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp == null) { auditArea.setText("No response."); return; }
                    auditArea.setText(resp.replace(Protocol.OK + "\n", ""));
                    auditArea.setCaretPosition(auditArea.getDocument().getLength());
                } catch (Exception e) { auditArea.setText("Error: " + e.getMessage()); }
            }
        };
        w.execute();
    }

    // ── Stats Tab ─────────────────────────────────────────────────────────────

    private JPanel buildStatsTab() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Client.BG_DARK);
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        statsArea = new JTextArea();
        statsArea.setFont(Client.FONT_MONO);
        statsArea.setForeground(Client.TEXT_PRIMARY);
        statsArea.setBackground(new Color(10, 14, 22));
        statsArea.setEditable(false);

        JScrollPane scroll = new JScrollPane(statsArea);
        scroll.setBorder(BorderFactory.createLineBorder(Client.BORDER_COLOR));
        p.add(scroll, BorderLayout.CENTER);

        JButton refresh = Client.makeButton("↻ Refresh Stats", Client.ACCENT_TEAL);
        refresh.addActionListener(e -> loadStats());
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bot.setBackground(Client.BG_DARK);
        bot.add(refresh);
        p.add(bot, BorderLayout.SOUTH);
        return p;
    }

    private void loadStats() {
        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(Protocol.GET_SYSTEM_STATS);
            }
            protected void done() {
                try {
                    String resp = get();
                    if (resp == null) { statsArea.setText("No response."); return; }
                    StringBuilder sb = new StringBuilder();
                    sb.append("=== DIST-VAULT System Status ===\n\n");
                    if (resp.startsWith(Protocol.OK)) {
                        String[] parts = resp.split("\\" + Protocol.SEP);
                        for (int i = 1; i < parts.length; i++) {
                            String[] kv = parts[i].split("=", 2);
                            if (kv.length == 2) {
                                sb.append(String.format("  %-22s %s%n", kv[0] + ":", kv[1]));
                            }
                        }
                    } else {
                        sb.append(resp);
                    }
                    statsArea.setText(sb.toString());
                } catch (Exception e) { statsArea.setText("Error: " + e.getMessage()); }
            }
        };
        w.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    void refresh() {
        loadUsers();
        loadAuditLog();
        loadStats();
    }

    private void styleTable(JTable t) {
        t.setFont(Client.FONT_BODY);
        t.setForeground(Client.TEXT_PRIMARY);
        t.setBackground(Client.BG_PANEL);
        t.setGridColor(Client.BORDER_COLOR);
        t.setRowHeight(28);
        t.setSelectionBackground(new Color(67, 130, 255, 80));
        t.setSelectionForeground(Client.TEXT_PRIMARY);
        t.getTableHeader().setFont(Client.FONT_SMALL);
        t.getTableHeader().setBackground(Client.BG_CARD);
        t.getTableHeader().setForeground(Client.TEXT_MUTED);
    }

    private JLabel label(String text) {
        return Client.makeLabel(text, Client.FONT_SMALL, Client.TEXT_MUTED);
    }

    private void showErr(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
}

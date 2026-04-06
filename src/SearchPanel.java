import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * Search Panel — search / browse records, download and view.
 */
class SearchPanel extends JPanel {

    private final Client app;
    private JTextField searchField;
    private JTextField patientFilterField;
    private DefaultTableModel tableModel;
    private JTable table;
    private JLabel statusLabel;

    private static final String[] COLS = {
        "File ID","File Name","Size","Patient ID","Doctor ID","Type","Date","Keywords","Chunks"
    };

    SearchPanel(Client app) {
        this.app = app;
        setBackground(Client.BG_DARK);
        setLayout(new BorderLayout());
        buildUI();
    }

    private void buildUI() {

        // ── Outer NORTH: title bar + search controls stacked ──────────────────
        JPanel outerNorth = new JPanel(new BorderLayout());
        outerNorth.setBackground(Client.BG_DARK);

        // Title / back button bar
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(Client.BG_PANEL);
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Client.BORDER_COLOR),
                BorderFactory.createEmptyBorder(14, 24, 14, 24)));

        JButton back = Client.makeButton("← Back", Client.BG_CARD);
        back.setFont(Client.FONT_SMALL);
        back.addActionListener(e -> app.showDash());

        titleBar.add(Client.makeLabel("🔍  Search Medical Records",
                Client.FONT_TITLE, Client.TEXT_PRIMARY), BorderLayout.WEST);
        titleBar.add(back, BorderLayout.EAST);

        // Search controls row
        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        searchBar.setBackground(Client.BG_DARK);
        searchBar.setBorder(BorderFactory.createEmptyBorder(8, 16, 4, 16));

        searchField = Client.makeField(22);
        searchField.setToolTipText("Search by keyword, record type, patient ID…");
        JButton searchBtn = Client.makeButton("Search", Client.ACCENT_BLUE);
        searchBtn.addActionListener(e -> doSearch());
        searchField.addActionListener(e -> doSearch());

        patientFilterField = Client.makeField(14);
        patientFilterField.setToolTipText("Filter by Patient ID");
        JButton listAllBtn = Client.makeButton("List All", Client.BG_CARD);
        listAllBtn.addActionListener(e -> doListAll());

        searchBar.add(Client.makeLabel("Keyword:", Client.FONT_SMALL, Client.TEXT_MUTED));
        searchBar.add(searchField);
        searchBar.add(searchBtn);
        searchBar.add(Box.createHorizontalStrut(16));
        searchBar.add(Client.makeLabel("Patient ID:", Client.FONT_SMALL, Client.TEXT_MUTED));
        searchBar.add(patientFilterField);
        searchBar.add(listAllBtn);

        // Status bar
        statusLabel = Client.makeLabel("  Click 'List All' or enter a keyword to search.",
                Client.FONT_SMALL, Client.TEXT_MUTED);
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        statusBar.setBackground(Client.BG_DARK);
        statusBar.add(statusLabel);

        JPanel controls = new JPanel(new BorderLayout());
        controls.setBackground(Client.BG_DARK);
        controls.add(searchBar,  BorderLayout.CENTER);
        controls.add(statusBar,  BorderLayout.SOUTH);

        outerNorth.add(titleBar, BorderLayout.NORTH);   // back button at very top
        outerNorth.add(controls, BorderLayout.CENTER);  // search controls below
        add(outerNorth, BorderLayout.NORTH);

        // ── Table ─────────────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLS, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        styleTable(table);

        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(Client.BG_PANEL);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Client.BORDER_COLOR));
        add(scroll, BorderLayout.CENTER);

        // ── Bottom action bar ─────────────────────────────────────────────────
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 10));
        bottomBar.setBackground(Client.BG_PANEL);
        bottomBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Client.BORDER_COLOR));

        JButton downloadBtn = Client.makeButton("⬇  Download & View Selected", Client.ACCENT_TEAL);
        downloadBtn.addActionListener(e -> doDownload());
        bottomBar.add(downloadBtn);
        add(bottomBar, BorderLayout.SOUTH);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void doSearch() {
        String kw = searchField.getText().trim();
        if (kw.isEmpty()) { doListAll(); return; }
        setStatus("Searching for: " + kw);
        fetchFiles(Protocol.SEARCH_FILES + Protocol.SEP + kw);
    }

    private void doListAll() {
        String pid = patientFilterField.getText().trim();
        setStatus("Loading records…");
        fetchFiles(Protocol.LIST_FILES + (pid.isEmpty() ? "" : Protocol.SEP + pid));
    }

    private void fetchFiles(String cmd) {
        SwingWorker<String, Void> w = new SwingWorker<>() {
            protected String doInBackground() throws Exception {
                return Client.MasterComm.send(cmd);
            }
            protected void done() {
                try {
                    String resp = get();
                    tableModel.setRowCount(0);
                    if (resp == null) { setStatus("No response from master server."); return; }
                    if (resp.startsWith(Protocol.DENIED)) {
                        setStatus("Access denied: " + resp); return;
                    }
                    String[] lines = resp.split("\n");
                    int count = 0;
                    for (String line : lines) {
                        if (line.equals(Protocol.OK) || line.isEmpty()
                                || line.startsWith(Protocol.ERROR)
                                || line.startsWith(Protocol.DENIED)) continue;
                        // fileId|fileName|fileSize|patientId|doctorId|recordType|date|keywords|version|chunks
                        String[] f = line.split("\\" + Protocol.SEP, -1);
                        if (f.length < 10) continue;
                        tableModel.addRow(new Object[]{
                            f[0], f[1], formatSize(parseLong(f[2])),
                            f[3], f[4], f[5], f[6], f[7], f[9]
                        });
                        count++;
                    }
                    setStatus(count == 0 ? "No records found." : count + " record(s) found.");
                } catch (Exception e) { setStatus("Error: " + e.getMessage()); }
            }
        };
        w.execute();
    }

    private void doDownload() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a record first.",
                    "No Selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String fileId    = (String) tableModel.getValueAt(row, 0);
        String fileName  = (String) tableModel.getValueAt(row, 1);
        String patientId = (String) tableModel.getValueAt(row, 3);

        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(System.getProperty("user.home"), fileName));
        fc.setDialogTitle("Save Record As");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File saveFile = fc.getSelectedFile();

        setStatus("Downloading " + fileName + "…");

        SwingWorker<Void, Void> w = new SwingWorker<>() {
            String error = null;
            protected Void doInBackground() {
                try {
                    // 1. Get chunk locations from master
                    String resp = Client.MasterComm.send(
                            Protocol.GET_LOCATIONS + Protocol.SEP + fileId);
                    if (resp == null || !resp.startsWith(Protocol.OK)) {
                        error = "Cannot get chunk locations: " + resp; return null;
                    }

                    // 2. Parse: OK|fileId|handle:r1,r2,r3|handle2:...
                    String[] parts = resp.split("\\" + Protocol.SEP);
                    if (parts.length < 3) {
                        error = "No chunk information returned for this file."; return null;
                    }

                    EncryptionManager enc = EncryptionManager.getInstance();
                    List<byte[]> chunkDatas = new ArrayList<>();

                    for (int i = 2; i < parts.length; i++) {
                        String chunkInfo = parts[i];
                        int colonIdx = chunkInfo.indexOf(':');
                        if (colonIdx < 0) { error = "Bad chunk info: " + chunkInfo; return null; }

                        long   handle   = Long.parseLong(chunkInfo.substring(0, colonIdx));
                        String repStr   = chunkInfo.substring(colonIdx + 1);
                        String[] replicas = repStr.isEmpty() ? new String[0] : repStr.split(",");

                        if (replicas.length == 0) {
                            error = "No replicas found for chunk " + handle
                                    + ". Make sure chunk servers are running.";
                            return null;
                        }

                        byte[] raw = null;
                        for (String replica : replicas) {
                            raw = Client.ChunkComm.getChunk(replica.trim(), handle);
                            if (raw != null) break;
                        }
                        if (raw == null) {
                            error = "Could not retrieve chunk " + handle
                                    + " from any replica. Check that chunk servers are running.";
                            return null;
                        }

                        byte[] decrypted = enc.decrypt(patientId, raw);
                        chunkDatas.add(decrypted);
                    }

                    // 3. Reassemble and save
                    try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                        for (byte[] chunk : chunkDatas) fos.write(chunk);
                    }

                } catch (Exception e) { error = e.getMessage(); }
                return null;
            }
            protected void done() {
                if (error != null) {
                    setStatus("❌ Download failed: " + error);
                    JOptionPane.showMessageDialog(SearchPanel.this,
                            "Download failed:\n" + error, "Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    setStatus("✅ Saved: " + saveFile.getAbsolutePath());
                    int opt = JOptionPane.showConfirmDialog(SearchPanel.this,
                            "File saved to:\n" + saveFile.getAbsolutePath() + "\n\nOpen it now?",
                            "Download Complete", JOptionPane.YES_NO_OPTION);
                    if (opt == JOptionPane.YES_OPTION) {
                        try { Desktop.getDesktop().open(saveFile); }
                        catch (Exception ex) {
                            JOptionPane.showMessageDialog(SearchPanel.this,
                                    "Cannot open file: " + ex.getMessage());
                        }
                    }
                }
            }
        };
        w.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStatus(String msg) { statusLabel.setText("  " + msg); }

    private void styleTable(JTable t) {
        t.setFont(Client.FONT_BODY);
        t.setForeground(Client.TEXT_PRIMARY);
        t.setBackground(Client.BG_PANEL);
        t.setGridColor(Client.BORDER_COLOR);
        t.setRowHeight(28);
        t.setSelectionBackground(new Color(67, 130, 255, 80));
        t.setSelectionForeground(Client.TEXT_PRIMARY);
        t.setShowVerticalLines(false);
        t.getTableHeader().setFont(Client.FONT_SMALL);
        t.getTableHeader().setBackground(Client.BG_CARD);
        t.getTableHeader().setForeground(Client.TEXT_MUTED);
        t.getTableHeader().setBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Client.BORDER_COLOR));
        int[] widths = {80, 160, 70, 90, 90, 100, 130, 120, 60};
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++)
            t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }

    private String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.2f MB", b / (1024.0 * 1024));
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}


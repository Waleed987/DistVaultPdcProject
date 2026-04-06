import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Upload Panel — select a file, enter metadata, encrypt & upload to chunk servers.
 */
class UploadPanel extends JPanel {

    private final Client app;
    private JTextField patientField, doctorField, keywordField;
    private JComboBox<String> recordTypeBox;
    private JLabel fileLabel;
    private File selectedFile;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    UploadPanel(Client app) {
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
        top.add(Client.makeLabel("📤  Upload Medical Record", Client.FONT_TITLE, Client.TEXT_PRIMARY), BorderLayout.WEST);
        top.add(back, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // Center form
        JPanel center = new JPanel(new GridBagLayout());
        center.setBackground(Client.BG_DARK);
        center.setBorder(BorderFactory.createEmptyBorder(30, 60, 30, 60));

        JPanel form = Client.card("Record Details");
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Client.BORDER_COLOR),
                BorderFactory.createEmptyBorder(24, 28, 24, 28)));

        // File chooser row
        JPanel fileRow = new JPanel(new BorderLayout(12, 0));
        fileRow.setBackground(Client.BG_CARD);
        fileLabel = Client.makeLabel("No file selected", Client.FONT_BODY, Client.TEXT_MUTED);
        JButton chooseBtn = Client.makeButton("Browse…", Client.ACCENT_TEAL);
        chooseBtn.setFont(Client.FONT_SMALL);
        chooseBtn.addActionListener(e -> chooseFile());
        fileRow.add(fileLabel, BorderLayout.CENTER);
        fileRow.add(chooseBtn, BorderLayout.EAST);

        patientField    = Client.makeField(30);
        doctorField     = Client.makeField(30);
        keywordField    = Client.makeField(30);
        recordTypeBox   = new JComboBox<>(new String[]{
                "X-Ray", "Blood Test", "MRI Scan", "CT Scan",
                "Prescription", "Lab Report", "Clinical Notes", "Other"});
        styleCombo(recordTypeBox);

        // Pre-fill doctor
        if (Client.currentUser != null) doctorField.setText(Client.currentUser);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setForeground(Client.ACCENT_BLUE);
        progressBar.setBackground(Client.BG_DARK);
        progressBar.setFont(Client.FONT_SMALL);
        progressBar.setMaximumSize(new Dimension(10000, 22));

        statusLabel = Client.makeLabel(" ", Client.FONT_SMALL, Client.ACCENT_TEAL);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);

        JButton uploadBtn = Client.makeButton("🔒  Encrypt & Upload", Client.ACCENT_BLUE);
        uploadBtn.setAlignmentX(LEFT_ALIGNMENT);
        uploadBtn.addActionListener(e -> doUpload());

        form.add(rowLabel("Select File"));            form.add(Box.createVerticalStrut(6));
        form.add(fileRow);                            form.add(Box.createVerticalStrut(16));
        form.add(rowLabel("Patient ID *"));           form.add(Box.createVerticalStrut(6));
        form.add(maxW(patientField));                 form.add(Box.createVerticalStrut(14));
        form.add(rowLabel("Doctor / Uploader ID *")); form.add(Box.createVerticalStrut(6));
        form.add(maxW(doctorField));                  form.add(Box.createVerticalStrut(14));
        form.add(rowLabel("Record Type"));            form.add(Box.createVerticalStrut(6));
        form.add(maxW(recordTypeBox));                form.add(Box.createVerticalStrut(14));
        form.add(rowLabel("Keywords (comma-separated)"));form.add(Box.createVerticalStrut(6));
        form.add(maxW(keywordField));                 form.add(Box.createVerticalStrut(24));
        form.add(progressBar);                        form.add(Box.createVerticalStrut(10));
        form.add(statusLabel);                        form.add(Box.createVerticalStrut(16));
        form.add(uploadBtn);

        center.add(form);
        add(center, BorderLayout.CENTER);
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select Medical Record File");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = fc.getSelectedFile();
            fileLabel.setText(selectedFile.getName()
                    + "  (" + formatSize(selectedFile.length()) + ")");
            fileLabel.setForeground(Client.TEXT_PRIMARY);
        }
    }

    private void doUpload() {
        if (!Protocol.ROLE_DOCTOR.equals(Client.currentRole)
                && !Protocol.ROLE_ADMIN.equals(Client.currentRole)) {
            showStatus("❌ Only Doctors and Admins can upload records.", Client.ACCENT_RED);
            return;
        }
        if (selectedFile == null) { showStatus("Please select a file first.", Client.ACCENT_RED); return; }
        String pid = patientField.getText().trim();
        String did = doctorField.getText().trim();
        if (pid.isEmpty() || did.isEmpty()) {
            showStatus("Patient ID and Doctor ID are required.", Client.ACCENT_RED); return;
        }

        String recordType = (String) recordTypeBox.getSelectedItem();
        String keywords   = keywordField.getText().trim();
        progressBar.setValue(0);
        showStatus("Preparing upload…", Client.ACCENT_TEAL);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            String errorMsg = null;
            protected Void doInBackground() {
                try {
                    publish(5);
                    // Read file completely (fis.read alone may not fill the buffer)
                    byte[] fileData;
                    try (FileInputStream fis = new FileInputStream(selectedFile)) {
                        fileData = fis.readAllBytes();
                    }
                    publish(15);

                    // Request chunk allocations from master
                    String cmd = String.join(Protocol.SEP,
                            Protocol.CREATE_FILE,
                            selectedFile.getName(),
                            String.valueOf(fileData.length),
                            pid, did, recordType, keywords);
                    String resp = Client.MasterComm.send(cmd);
                    if (resp == null || !resp.startsWith(Protocol.OK)) {
                        errorMsg = "Master error: " + resp; return null;
                    }
                    publish(25);

                    // Parse chunk assignments: OK|fileId|handle:r1,r2,r3|...
                    String[] parts = resp.split("\\" + Protocol.SEP);
                    // parts[0]=OK, parts[1]=fileId, parts[2..]=handle:replicas
                    int numChunks = parts.length - 2;
                    int chunkSize = Protocol.CHUNK_SIZE_BYTES;
                    EncryptionManager enc = EncryptionManager.getInstance();

                    for (int i = 0; i < numChunks; i++) {
                        String chunkInfo = parts[i + 2];
                        int colonIdx = chunkInfo.indexOf(':');
                        long handle = Long.parseLong(colonIdx >= 0
                                ? chunkInfo.substring(0, colonIdx)
                                : chunkInfo);
                        String repStr = colonIdx >= 0 ? chunkInfo.substring(colonIdx + 1) : "";
                        String[] replicas = repStr.isEmpty() ? new String[0] : repStr.split(",");

                        if (replicas.length == 0) {
                            errorMsg = "No chunk servers available. " +
                                    "Please start at least one Chunk Server and try again.";
                            return null;
                        }
                        int offset = i * chunkSize;
                        int len = Math.min(chunkSize, fileData.length - offset);
                        byte[] chunkData = new byte[len];
                        System.arraycopy(fileData, offset, chunkData, 0, len);

                        // Encrypt
                        byte[] encrypted = enc.encrypt(pid, chunkData);

                        // Upload to all replicas
                        boolean stored = false;
                        for (String replica : replicas) {
                            if (Client.ChunkComm.storeChunk(replica, handle, encrypted)) {
                                stored = true;
                            }
                        }
                        if (!stored) {
                            errorMsg = "Failed to store chunk " + handle
                                    + " on any chunk server. Check server logs.";
                            return null;
                        }
                        int progress = 25 + (int)((i + 1.0) / numChunks * 70);
                        publish(progress);
                    }
                    publish(100);
                } catch (Exception e) {
                    errorMsg = e.getMessage();
                }
                return null;
            }
            protected void process(List<Integer> chunks) {
                progressBar.setValue(chunks.get(chunks.size() - 1));
            }
            protected void done() {
                if (errorMsg != null) {
                    showStatus("❌ " + errorMsg, Client.ACCENT_RED);
                } else {
                    showStatus("✅ Upload complete! File encrypted and distributed.", Client.ACCENT_TEAL);
                    selectedFile = null;
                    fileLabel.setText("No file selected");
                    fileLabel.setForeground(Client.TEXT_MUTED);
                }
            }
        };
        worker.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JLabel rowLabel(String text) {
        JLabel l = Client.makeLabel(text, Client.FONT_SMALL, Client.TEXT_MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private Component maxW(JComponent c) {
        c.setMaximumSize(new Dimension(10000, 40));
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private void styleCombo(JComboBox<String> cb) {
        cb.setFont(Client.FONT_BODY);
        cb.setBackground(Client.BG_DARK);
        cb.setForeground(Client.TEXT_PRIMARY);
        cb.setBorder(BorderFactory.createLineBorder(Client.BORDER_COLOR));
    }

    private void showStatus(String msg, Color color) {
        statusLabel.setText(msg);
        statusLabel.setForeground(color);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }
}

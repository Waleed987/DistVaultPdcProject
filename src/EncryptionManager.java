import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.Arrays;

/**
 * DIST-VAULT Encryption Manager
 *
 * Provides AES-256-CBC encryption/decryption for medical record chunks.
 * Each patient gets a unique AES key stored in a JCEKS KeyStore (distvault.ks).
 * A random 16-byte IV is prepended to every encrypted chunk.
 */
public class EncryptionManager {

    private static final String KEYSTORE_FILE = "distvault.ks";
    private static final String KEYSTORE_TYPE = "JCEKS";
    private static final char[] KS_PASSWORD   = "DVault$KSpass!2024".toCharArray();
    private static final String ALGORITHM      = "AES/CBC/PKCS5Padding";
    private static final int    KEY_SIZE_BITS  = 256;
    private static final int    IV_SIZE_BYTES  = 16;

    private static EncryptionManager instance;
    private KeyStore keyStore;

    private EncryptionManager() {
        loadOrCreateKeystore();
    }

    public static synchronized EncryptionManager getInstance() {
        if (instance == null) instance = new EncryptionManager();
        return instance;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Encrypt raw bytes for a given patient.
     * If no key exists for the patient, one is generated and stored.
     * @return IV (16 bytes) + encrypted ciphertext
     */
    public byte[] encrypt(String patientId, byte[] plaintext) throws Exception {
        SecretKey key = getOrCreateKey(patientId);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        byte[] iv = new byte[IV_SIZE_BYTES];
        new SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] ciphertext = cipher.doFinal(plaintext);
        // Prepend IV
        byte[] result = new byte[IV_SIZE_BYTES + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, IV_SIZE_BYTES);
        System.arraycopy(ciphertext, 0, result, IV_SIZE_BYTES, ciphertext.length);
        return result;
    }

    /**
     * Decrypt bytes (IV prepended) for a given patient.
     * @param data IV (16 bytes) + ciphertext
     * @return plaintext bytes
     */
    public byte[] decrypt(String patientId, byte[] data) throws Exception {
        SecretKey key = getOrCreateKey(patientId);
        byte[] iv         = Arrays.copyOfRange(data, 0, IV_SIZE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(data, IV_SIZE_BYTES, data.length);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        return cipher.doFinal(ciphertext);
    }

    /**
     * Check whether a patient key exists in the keystore.
     */
    public boolean hasKey(String patientId) {
        try {
            return keyStore.containsAlias("patient-" + patientId);
        } catch (KeyStoreException e) {
            return false;
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private SecretKey getOrCreateKey(String patientId) throws Exception {
        String alias = "patient-" + patientId;
        if (keyStore.containsAlias(alias)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    keyStore.getEntry(alias, new KeyStore.PasswordProtection(KS_PASSWORD));
            return entry.getSecretKey();
        }
        // Generate new key
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(KEY_SIZE_BITS, new SecureRandom());
        SecretKey newKey = kg.generateKey();
        keyStore.setEntry(alias,
                new KeyStore.SecretKeyEntry(newKey),
                new KeyStore.PasswordProtection(KS_PASSWORD));
        saveKeystore();
        return newKey;
    }

    private void loadOrCreateKeystore() {
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            File ksFile = new File(KEYSTORE_FILE);
            if (ksFile.exists()) {
                try (InputStream is = new FileInputStream(ksFile)) {
                    keyStore.load(is, KS_PASSWORD);
                }
            } else {
                keyStore.load(null, KS_PASSWORD);
                saveKeystore();
            }
        } catch (Exception e) {
            throw new RuntimeException("KeyStore error: " + e.getMessage(), e);
        }
    }

    private void saveKeystore() throws Exception {
        try (OutputStream os = new FileOutputStream(KEYSTORE_FILE)) {
            keyStore.store(os, KS_PASSWORD);
        }
    }
}

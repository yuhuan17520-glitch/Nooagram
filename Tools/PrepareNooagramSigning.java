import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;

// Source-file launcher. Run only in the trusted private signing job. Never prints secrets.
class PrepareNooagramSigning {
    public static void main(String[] args) throws Exception {
        if (!"yuhuan17520-glitch/Nooagram".equals(System.getenv("GITHUB_REPOSITORY"))) {
            throw new IllegalStateException("Signing is restricted to the Nooagram repository");
        }
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(Base64.getDecoder().decode(required("LOCAL_PROPERTIES"))));
        byte[] bytes = Base64.getDecoder().decode(required("NOOAGRAM_KEYSTORE_BASE64"));
        String password = properties.getProperty("KEYSTORE_PASS");
        String alias = properties.getProperty("ALIAS_NAME");
        if (password == null || alias == null || properties.getProperty("ALIAS_PASS") == null) {
            throw new IllegalStateException("Signing properties are incomplete");
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(new ByteArrayInputStream(bytes), password.toCharArray());
        if (!store.isKeyEntry(alias)) throw new IllegalStateException("Signing alias missing");
        byte[] certificate = store.getCertificate(alias).getEncoded();
        HexFormat hex = HexFormat.of().withUpperCase();
        String sha256 = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(certificate));
        // Verified against the last public Nooagram 125000081 APK, not a new key.
        if (!"A6FF043A4808DC097B9C62CB3939536A055B4567441A7EA61A78B443640F8088".equals(sha256)) {
            throw new IllegalStateException("Signing certificate does not match the previous public release");
        }
        String sha1 = hex.formatHex(MessageDigest.getInstance("SHA-1").digest(certificate));
        Path keyFile = Path.of(required("RUNNER_TEMP"), "nooagram-candidate.keystore");
        Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.writeString(Path.of(required("GITHUB_ENV")),
                "NOOAGRAM_KEYSTORE_PATH=" + keyFile + "\nNOOAGRAM_NATIVE_SIGNER_SHA1=" + sha1 + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("PASS signing certificate matches the previous public Nooagram release");
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}

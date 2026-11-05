import com.google.wireless.android.vending.developer.signing.tools.extern.export.ExportEncryptedPrivateKeyTool;
import com.google.wireless.android.vending.developer.signing.tools.extern.export.KeystoreKey;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipFile;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Calls the pinned PEPK API; passwords never enter its CLI argument/error-printing path. */
public final class PlaySigningTransfer {
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        return value;
    }

    public static void main(String[] args) {
        String stage = "inputs";
        char[] password = null;
        Path output = null;
        Path temporary = null;
        int exit = 0;
        try {
            if (args.length != 4 || !args[2].matches("[0-9a-f]{64}") ||
                    !args[3].matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            Path recipient = Path.of(args[0]).toRealPath();
            output = Path.of(args[1]).toAbsolutePath().normalize();
            Path receipt = output.resolveSibling(output.getFileName() + ".json");
            if (!Files.isDirectory(output.getParent()) || Files.exists(output, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            stage = "recipient";
            if (Files.size(recipient) > 16384) throw new IllegalArgumentException();
            byte[] pem = Files.readAllBytes(recipient);
            if (pem.length > 16384 || !hash(pem).equals(args[2]) ||
                    !new String(pem, StandardCharsets.US_ASCII).startsWith("-----BEGIN PUBLIC KEY-----")) {
                throw new IllegalArgumentException();
            }
            stage = "certificate";
            password = required("ANDROID_RELEASE_PASSWORD").toCharArray();
            Path keystore = Path.of(required("PLAY_TRANSFER_KEYSTORE")).toRealPath();
            String alias = required("PLAY_TRANSFER_KEY_ALIAS");
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keystore)) { store.load(input, password); }
            var certificate = store.getCertificate(alias);
            if (certificate == null || !hash(certificate.getEncoded()).equals(args[3]) ||
                    !(certificate.getPublicKey() instanceof RSAPublicKey rsa) ||
                    rsa.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            PrivateKey key = (PrivateKey) store.getKey(alias, password);
            byte[] challenge = "MeshPilot signing transfer identity check".getBytes(StandardCharsets.US_ASCII);
            Signature proof = Signature.getInstance("SHA256withRSA");
            proof.initSign(key); proof.update(challenge);
            byte[] signed = proof.sign();
            proof.initVerify(certificate); proof.update(challenge);
            if (!proof.verify(signed)) throw new IllegalArgumentException();

            stage = "encryption";
            Security.addProvider(new BouncyCastleProvider());
            temporary = Files.createTempDirectory(output.getParent(), ".play-signing-transfer-");
            Path encryptedZip = temporary.resolve("transfer.zip");
            new ExportEncryptedPrivateKeyTool().run(true, recipient.toString(), encryptedZip.toString(),
                    new KeystoreKey(keystore, alias, password, password), Optional.empty(), true);
            stage = "archive";
            if (Files.size(encryptedZip) > 131072) throw new IllegalArgumentException();
            try (ZipFile zip = new ZipFile(encryptedZip.toFile())) {
                var names = zip.stream().map(e -> e.getName()).collect(java.util.stream.Collectors.toSet());
                if (zip.size() != 2 || !names.equals(Set.of("encryptedPrivateKey", "certificate.pem"))) {
                    throw new IllegalArgumentException();
                }
                byte[] encrypted;
                try (var in = zip.getInputStream(zip.getEntry("encryptedPrivateKey"))) {
                    encrypted = in.readNBytes(65537);
                }
                if (encrypted.length < 256 || encrypted.length > 65536 ||
                        Arrays.equals(encrypted, key.getEncoded())) throw new IllegalArgumentException();
                byte[] exportedCertificate;
                try (var in = zip.getInputStream(zip.getEntry("certificate.pem"))) {
                    exportedCertificate = in.readNBytes(16385);
                }
                if (exportedCertificate.length > 16384) throw new IllegalArgumentException();
                var exported = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(exportedCertificate));
                if (!hash(exported.getEncoded()).equals(args[3])) throw new IllegalArgumentException();
            }
            String json = "{\"format\":\"PEPK_RSA_AES\",\"recipientSha256\":\"" + args[2] +
                    "\",\"certificateSha256\":\"" + args[3] + "\",\"zipSha256\":\"" +
                    hash(Files.readAllBytes(encryptedZip)) + "\",\"zipSize\":" + Files.size(encryptedZip) + "}\n";
            stage = "publication";
            // Default copy refuses an existing destination, including one created after the first check.
            Files.copy(encryptedZip, output);
            Files.writeString(receipt, json, java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("Encrypted signing transfer verified; certificate SHA-256: " + args[3]);
        } catch (Exception failure) {
            // No exception messages, stack traces, environment values or argument arrays in logs.
            System.err.println("Signing transfer failed at stage: " + stage);
            exit = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.resolve("transfer.zip"));
                    Files.delete(temporary);
                } catch (Exception ignored) {
                    System.err.println("Signing transfer temporary cleanup failed");
                    exit = 1;
                }
            }
        }
        if (exit != 0) System.exit(exit);
    }
}

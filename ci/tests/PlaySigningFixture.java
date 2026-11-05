import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.engines.AESWrapPadEngine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

/** Creates throwaway signing material and verifies a PEPK transfer archive. */
public final class PlaySigningFixture {
    private static final String DEFAULT_ALIAS = "fixture";
    private static final String RECIPIENT_ALIAS = "recipient";
    private static final int RSA_BITS = 2048;

    private PlaySigningFixture() {}

    public static void main(String[] args) {
        String stage = "arguments";
        char[] password = null;
        int exit = 0;
        try {
            if (args.length == 0) throw new IllegalArgumentException();
            Security.addProvider(new BouncyCastleProvider());
            String mode = args[0];
            if ("generate".equals(mode)) {
                stage = "generate";
                if (args.length != 2) throw new IllegalArgumentException();
                password = required("ANDROID_RELEASE_PASSWORD").toCharArray();
                generate(Path.of(args[1]), password);
            } else if ("mismatch".equals(mode)) {
                stage = "mismatch";
                if (args.length != 2) throw new IllegalArgumentException();
                password = required("ANDROID_RELEASE_PASSWORD").toCharArray();
                mismatch(Path.of(args[1]), password);
            } else if ("verify".equals(mode)) {
                stage = "verify";
                if (args.length != 4) throw new IllegalArgumentException();
                password = required("ANDROID_RELEASE_PASSWORD").toCharArray();
                verify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), password);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (Exception failure) {
            System.err.println("Fixture failed at stage: " + stage);
            exit = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
        if (exit != 0) System.exit(exit);
    }

    private static void generate(Path directory, char[] password) throws Exception {
        Files.createDirectories(directory);
        String alias = envOr("PLAY_TRANSFER_KEY_ALIAS", DEFAULT_ALIAS);
        KeyPair source = rsa();
        X509Certificate sourceCertificate = selfSigned(source, "MeshPilot-Source-Fixture");
        KeyPair recipient = rsa();
        X509Certificate recipientCertificate =
                selfSigned(recipient, "MeshPilot-Recipient-Fixture");

        Path keystore = directory.resolve("file.p12");
        writeStore(keystore, alias, source.getPrivate(), sourceCertificate, password);
        Path recipientPrivate = directory.resolve("recipient-private.p12");
        writeStore(
                recipientPrivate,
                RECIPIENT_ALIAS,
                recipient.getPrivate(),
                recipientCertificate,
                password);
        Path recipientPublic = directory.resolve("recipient-public.pem");
        Files.writeString(
                recipientPublic, publicPem(recipient.getPublic()), StandardCharsets.US_ASCII);

        System.out.println(
                "{\"keystore\":\"file.p12\",\"alias\":\""
                        + json(alias)
                        + "\",\"recipientPublicPem\":\"recipient-public.pem\""
                        + ",\"recipientPrivatePkcs12\":\"recipient-private.p12\""
                        + ",\"certificateSha256\":\""
                        + sha256(sourceCertificate.getEncoded())
                        + "\",\"recipientSha256\":\""
                        + sha256(Files.readAllBytes(recipientPublic))
                        + "\"}");
    }

    private static void mismatch(Path directory, char[] password) throws Exception {
        String alias = envOr("PLAY_TRANSFER_KEY_ALIAS", DEFAULT_ALIAS);
        KeyStore source = readStore(directory.resolve("file.p12"), password);
        Certificate certificate = source.getCertificate(alias);
        if (certificate == null) throw new IllegalArgumentException();
        KeyPair replacement = rsa();
        Path mismatch = directory.resolve("mismatch.p12");
        writeStore(mismatch, alias, replacement.getPrivate(), certificate, password);
        System.out.println(
                "{\"keystore\":\"mismatch.p12\",\"alias\":\""
                        + json(alias)
                        + "\",\"certificateSha256\":\""
                        + sha256(certificate.getEncoded())
                        + "\"}");
    }

    private static void verify(Path zipPath, Path keystorePath, Path recipientPath, char[] password)
            throws Exception {
        String alias = envOr("PLAY_TRANSFER_KEY_ALIAS", DEFAULT_ALIAS);
        KeyStore source = readStore(keystorePath, password);
        PrivateKey sourceKey = (PrivateKey) source.getKey(alias, password);
        if (sourceKey == null) throw new IllegalArgumentException();

        KeyStore recipient = readStore(recipientPath, password);
        PrivateKey recipientKey = (PrivateKey) recipient.getKey(RECIPIENT_ALIAS, password);
        if (!(recipientKey instanceof RSAPrivateKey rsa)) throw new IllegalArgumentException();

        byte[] encrypted;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            if (!entries.hasMoreElements()
                    || !"encryptedPrivateKey".equals(entries.nextElement().getName())) {
                throw new IllegalArgumentException();
            }
            var entry = zip.getEntry("encryptedPrivateKey");
            if (entry == null) throw new IllegalArgumentException();
            try (InputStream input = zip.getInputStream(entry)) {
                encrypted = input.readAllBytes();
            }
        }

        int encryptedKeyBytes = (rsa.getModulus().bitLength() + 7) / 8;
        if (encrypted.length <= encryptedKeyBytes) throw new IllegalArgumentException();
        byte[] wrappedAesKey = Arrays.copyOfRange(encrypted, 0, encryptedKeyBytes);
        byte[] wrappedPrivateKey = Arrays.copyOfRange(encrypted, encryptedKeyBytes, encrypted.length);

        Cipher cipher = Cipher.getInstance("RSA/NONE/OAEPWithSHA1AndMGF1Padding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, rsa);
        byte[] aesKey = cipher.doFinal(wrappedAesKey);

        AESWrapPadEngine engine = new AESWrapPadEngine();
        engine.init(false, new KeyParameter(aesKey));
        byte[] privateKeyBytes = engine.unwrap(wrappedPrivateKey, 0, wrappedPrivateKey.length);
        if (!MessageDigest.isEqual(privateKeyBytes, sourceKey.getEncoded())) {
            throw new IllegalArgumentException();
        }
        System.out.println("PASS");
    }

    private static void writeStore(
            Path path,
            String alias,
            PrivateKey key,
            Certificate certificate,
            char[] password)
            throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, key, password, new Certificate[] {certificate});
        try (OutputStream output = Files.newOutputStream(path)) {
            store.store(output, password);
        }
    }

    private static KeyStore readStore(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_BITS);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair pair, String commonName) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 3_600_000L);
        Date notAfter = new Date(now + 3_650L * 24L * 60L * 60L * 1000L);
        X500Name name = new X500Name("CN=" + commonName);
        BigInteger serial = BigInteger.valueOf(now).multiply(BigInteger.valueOf(1000L));
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        name, serial, notBefore, notAfter, name, pair.getPublic());
        ContentSigner signer =
                new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider("BC")
                        .build(pair.getPrivate());
        X509Certificate certificate =
                new JcaX509CertificateConverter()
                        .setProvider("BC")
                        .getCertificate(builder.build(signer));
        certificate.verify(pair.getPublic());
        return certificate;
    }

    private static String publicPem(PublicKey key) throws Exception {
        StringWriter text = new StringWriter();
        try (PemWriter writer = new PemWriter(text)) {
            writer.writeObject(new PemObject("PUBLIC KEY", key.getEncoded()));
        }
        return text.toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        return value;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Signs a source-built APK with the existing publisher identity; no key export. */
class SignVerifiedApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("properties unsigned.apk signed.apk apksigner.jar");
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of(args[0]))) { p.load(in); }
        Path store = Path.of(p.getProperty("storeFile"));
        if (!store.isAbsolute()) store = Path.of(args[0]).toAbsolutePath().getParent().resolve(store).normalize();
        String alias = p.getProperty("keyAlias");
        KeyStore ks = KeyStore.getInstance(store.toFile(), p.getProperty("storePassword").toCharArray());
        String fp = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(alias).getEncoded()));
        if (!fp.equals("570b4ad5bbe8ab617b8164226b07be21e8011d977bbe7c4e7070a5b1581e37f0"))
            throw new SecurityException("Unexpected APK signing certificate");
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-jar", args[3], "sign", "--ks", store.toString(), "--ks-key-alias", alias,
            "--ks-pass", "env:VIKIFY_APK_STORE_PASS", "--key-pass", "env:VIKIFY_APK_KEY_PASS",
            "--v4-signing-enabled", "false", "--out", args[2], args[1]);
        process.environment().put("VIKIFY_APK_STORE_PASS", p.getProperty("storePassword"));
        process.environment().put("VIKIFY_APK_KEY_PASS", p.getProperty("keyPassword"));
        int status = process.inheritIO().start().waitFor();
        if (status != 0) throw new IOException("APK signing failed: " + status);
        System.out.println("Signed with existing publisher certificate: " + fp);
    }
}

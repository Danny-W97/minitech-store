import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Disposable E2E fixture reproducing the pre-A22 cipher, with inputs through stdin. */
class LegacyMfaCipher {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        byte[] key = MessageDigest.getInstance("SHA-256").digest(input.readLine().getBytes(StandardCharsets.UTF_8));
        String secret = input.readLine();
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        System.out.print(Base64.getUrlEncoder().withoutPadding().encodeToString(combined));
    }
}

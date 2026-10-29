package friendchat.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** NIP-44 v2 (ECDH + HKDF + ChaCha20 + HMAC-SHA256) plus the ChaCha20-Poly1305 helpers used for media and groups. */
public final class Nip44 {
    private Nip44() { }

    private static final SecureRandom RNG = new SecureRandom();

    private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) m.update(p);
        return m.doFinal();
    }

    /** Pair key, computed once per friend and cached by the caller (the ECDH is the expensive part). */
    public static byte[] conversationKey(byte[] sk, byte[] pubX) {
        try {
            return hmac("nip44-v2".getBytes(StandardCharsets.UTF_8), Secp256k1.ecdh(sk, pubX));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static int paddedLen(int len) {
        if (len <= 32) return 32;
        int next = 1 << (32 - Integer.numberOfLeadingZeros(len - 1));
        int chunk = next <= 256 ? 32 : next / 8;
        return chunk * (((len - 1) / chunk) + 1);
    }

    public static String encrypt(String plain, byte[] convKey) {
        return encrypt(plain, convKey, null);
    }

    static String encrypt(String plain, byte[] convKey, byte[] fixedNonce) {
        try {
            byte[] msg = plain.getBytes(StandardCharsets.UTF_8);
            if (msg.length < 1 || msg.length > 65535) throw new IllegalArgumentException("message size");
            byte[] nonce = fixedNonce != null ? fixedNonce : new byte[32];
            if (fixedNonce == null) RNG.nextBytes(nonce);
            byte[][] k = keys(convKey, nonce);
            byte[] padded = new byte[2 + paddedLen(msg.length)];
            padded[0] = (byte) (msg.length >> 8);
            padded[1] = (byte) msg.length;
            System.arraycopy(msg, 0, padded, 2, msg.length);
            Cipher c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k[0], "ChaCha20"), new ChaCha20ParameterSpec(k[1], 0));
            byte[] ct = c.doFinal(padded);
            byte[] mac = hmac(k[2], nonce, ct);
            byte[] out = new byte[1 + 32 + ct.length + 32];
            out[0] = 2;
            System.arraycopy(nonce, 0, out, 1, 32);
            System.arraycopy(ct, 0, out, 33, ct.length);
            System.arraycopy(mac, 0, out, 33 + ct.length, 32);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the plaintext, or null when the payload is not valid for this key. */
    public static String decrypt(String payload, byte[] convKey) {
        try {
            if (payload == null || payload.length() < 132 || payload.startsWith("#")) return null;
            byte[] d = Base64.getDecoder().decode(payload);
            if (d.length < 99 || d[0] != 2) return null;
            byte[] nonce = Arrays.copyOfRange(d, 1, 33);
            byte[] ct = Arrays.copyOfRange(d, 33, d.length - 32);
            byte[] mac = Arrays.copyOfRange(d, d.length - 32, d.length);
            byte[][] k = keys(convKey, nonce);
            if (!MessageDigest.isEqual(mac, hmac(k[2], nonce, ct))) return null;
            Cipher c = Cipher.getInstance("ChaCha20");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k[0], "ChaCha20"), new ChaCha20ParameterSpec(k[1], 0));
            byte[] padded = c.doFinal(ct);
            int len = ((padded[0] & 0xFF) << 8) | (padded[1] & 0xFF);
            if (len < 1 || padded.length != 2 + paddedLen(len)) return null;
            return new String(padded, 2, len, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** HKDF-expand(convKey, nonce, 76) split into chacha key, chacha nonce, hmac key. */
    private static byte[][] keys(byte[] convKey, byte[] nonce) throws GeneralSecurityException {
        byte[] t1 = hmac(convKey, nonce, new byte[]{1});
        byte[] t2 = hmac(convKey, t1, nonce, new byte[]{2});
        byte[] t3 = hmac(convKey, t2, nonce, new byte[]{3});
        byte[] okm = new byte[96];
        System.arraycopy(t1, 0, okm, 0, 32);
        System.arraycopy(t2, 0, okm, 32, 32);
        System.arraycopy(t3, 0, okm, 64, 32);
        return new byte[][]{Arrays.copyOfRange(okm, 0, 32), Arrays.copyOfRange(okm, 32, 44), Arrays.copyOfRange(okm, 44, 76)};
    }

    // ---- symmetric blobs (group messages, media): nonce(12) || ciphertext || tag(16)
    public static byte[] newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    public static byte[] seal(byte[] key, byte[] plain) {
        try {
            byte[] nonce = new byte[12];
            RNG.nextBytes(nonce);
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(nonce, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the plaintext, or null when the key is wrong or the data was tampered with. */
    public static byte[] open(byte[] key, byte[] blob) {
        try {
            if (blob == null || blob.length < 28) return null;
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(Arrays.copyOfRange(blob, 0, 12)));
            return c.doFinal(blob, 12, blob.length - 12);
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }
}

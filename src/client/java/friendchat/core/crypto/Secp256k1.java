package friendchat.core.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * secp256k1 with BIP-340 Schnorr signatures and x-only ECDH (what Nostr uses), on plain BigInteger so the mod has no
 * crypto dependency. Jacobian coordinates keep a scalar multiplication to about a millisecond; it only ever runs on
 * the background thread.
 */
public final class Secp256k1 {
    private Secp256k1() { }

    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger[] G = {GX, GY, BigInteger.ONE};
    private static final BigInteger TWO = BigInteger.TWO, THREE = BigInteger.valueOf(3), SEVEN = BigInteger.valueOf(7);
    private static final SecureRandom RNG = new SecureRandom();

    // ---- Jacobian arithmetic (a = 0)
    private static boolean inf(BigInteger[] p) { return p[2].signum() == 0; }

    private static BigInteger[] dbl(BigInteger[] p) {
        if (inf(p) || p[1].signum() == 0) return new BigInteger[]{BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger y2 = p[1].multiply(p[1]).mod(P);
        BigInteger s = p[0].multiply(y2).shiftLeft(2).mod(P);
        BigInteger m = p[0].multiply(p[0]).multiply(THREE).mod(P);
        BigInteger x = m.multiply(m).subtract(s.shiftLeft(1)).mod(P);
        BigInteger y = m.multiply(s.subtract(x)).subtract(y2.multiply(y2).shiftLeft(3)).mod(P);
        BigInteger z = p[1].multiply(p[2]).shiftLeft(1).mod(P);
        return new BigInteger[]{x, y, z};
    }

    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (inf(a)) return b;
        if (inf(b)) return a;
        BigInteger z1 = a[2].multiply(a[2]).mod(P), z2 = b[2].multiply(b[2]).mod(P);
        BigInteger u1 = a[0].multiply(z2).mod(P), u2 = b[0].multiply(z1).mod(P);
        BigInteger s1 = a[1].multiply(z2).multiply(b[2]).mod(P), s2 = b[1].multiply(z1).multiply(a[2]).mod(P);
        if (u1.equals(u2)) return s1.equals(s2) ? dbl(a) : new BigInteger[]{BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        BigInteger h = u2.subtract(u1).mod(P), r = s2.subtract(s1).mod(P);
        BigInteger h2 = h.multiply(h).mod(P), h3 = h2.multiply(h).mod(P), v = u1.multiply(h2).mod(P);
        BigInteger x = r.multiply(r).subtract(h3).subtract(v.shiftLeft(1)).mod(P);
        BigInteger y = r.multiply(v.subtract(x)).subtract(s1.multiply(h3)).mod(P);
        BigInteger z = h.multiply(a[2]).multiply(b[2]).mod(P);
        return new BigInteger[]{x, y, z};
    }

    private static BigInteger[] mul(BigInteger k, BigInteger[] p) {
        BigInteger[] r = {BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    private static BigInteger[] affine(BigInteger[] p) {
        if (inf(p)) return null;
        BigInteger zi = p[2].modInverse(P), zi2 = zi.multiply(zi).mod(P);
        return new BigInteger[]{p[0].multiply(zi2).mod(P), p[1].multiply(zi2).multiply(zi).mod(P)};
    }

    private static BigInteger[] jac(BigInteger x, BigInteger y) { return new BigInteger[]{x, y, BigInteger.ONE}; }

    /** Point with the given x and an even y, or null when x is not on the curve. */
    private static BigInteger[] lift(BigInteger x) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) return null;
        BigInteger c = x.pow(3).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.multiply(y).mod(P).equals(c)) return null;
        return jac(x, y.testBit(0) ? P.subtract(y) : y);
    }

    // ---- helpers
    static BigInteger num(byte[] b) { return new BigInteger(1, b); }

    static byte[] b32(BigInteger v) {
        byte[] raw = v.toByteArray(), out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    static byte[] sha(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] tagged(String tag, byte[]... parts) {
        byte[] t = sha(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = t;
        all[1] = t;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha(all);
    }

    // ---- public API
    public static boolean validSecret(byte[] sk) {
        if (sk == null || sk.length != 32) return false;
        BigInteger d = num(sk);
        return d.signum() > 0 && d.compareTo(N) < 0;
    }

    public static byte[] newSecret() {
        while (true) {
            byte[] sk = new byte[32];
            RNG.nextBytes(sk);
            if (validSecret(sk)) return sk;
        }
    }

    /** x-only public key (32 bytes). */
    public static byte[] publicKey(byte[] sk) {
        return b32(affine(mul(num(sk), G))[0]);
    }

    public static byte[] sign(byte[] msg32, byte[] sk) {
        byte[] aux = new byte[32];
        RNG.nextBytes(aux);
        return sign(msg32, sk, aux);
    }

    public static byte[] sign(byte[] msg32, byte[] sk, byte[] aux) {
        BigInteger d0 = num(sk);
        BigInteger[] pt = affine(mul(d0, G));
        BigInteger d = pt[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] px = b32(pt[0]);
        byte[] t = b32(d.xor(num(tagged("BIP0340/aux", aux))));
        BigInteger k0 = num(tagged("BIP0340/nonce", t, px, msg32)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("bad nonce");
        BigInteger[] r = affine(mul(k0, G));
        BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = b32(r[0]);
        BigInteger e = num(tagged("BIP0340/challenge", rx, px, msg32)).mod(N);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(b32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        return sig;
    }

    public static boolean verify(byte[] msg32, byte[] sig, byte[] pubX) {
        try {
            if (sig == null || sig.length != 64 || pubX == null || pubX.length != 32) return false;
            BigInteger[] pt = lift(num(pubX));
            BigInteger r = num(Arrays.copyOfRange(sig, 0, 32)), s = num(Arrays.copyOfRange(sig, 32, 64));
            if (pt == null || r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
            BigInteger e = num(tagged("BIP0340/challenge", b32(r), pubX, msg32)).mod(N);
            BigInteger[] negE = mul(N.subtract(e), pt);
            BigInteger[] res = affine(add(mul(s, G), negE));
            return res != null && !res[1].testBit(0) && res[0].equals(r);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** x coordinate of sk * lift(pubX): the raw ECDH secret Nostr's NIP-44 expects. */
    public static byte[] ecdh(byte[] sk, byte[] pubX) {
        BigInteger[] pt = lift(num(pubX));
        if (pt == null) throw new IllegalArgumentException("invalid public key");
        return b32(affine(mul(num(sk), pt))[0]);
    }
}

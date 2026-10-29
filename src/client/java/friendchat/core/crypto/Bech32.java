package friendchat.core.crypto;

import java.util.Locale;

/** Bech32 (BIP-173) for "npub1..." friend codes. */
public final class Bech32 {
    private Bech32() { }

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private static int polymod(int[] v) {
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        for (int x : v) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ x;
            for (int i = 0; i < 5; i++) if (((b >>> i) & 1) != 0) chk ^= gen[i];
        }
        return chk;
    }

    private static int[] expand(String hrp) {
        int[] r = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            r[i] = hrp.charAt(i) >> 5;
            r[i + hrp.length() + 1] = hrp.charAt(i) & 31;
        }
        return r;
    }

    private static int[] regroup(byte[] data, int from, int to, boolean pad) {
        int acc = 0, bits = 0, maxv = (1 << to) - 1;
        int[] out = new int[(data.length * from + to - 1) / to + 1];
        int n = 0;
        for (byte b : data) {
            acc = (acc << from) | (b & ((1 << from) - 1) & 0xFF);
            bits += from;
            while (bits >= to) {
                bits -= to;
                out[n++] = (acc >>> bits) & maxv;
            }
            acc &= (1 << bits) - 1;
        }
        if (pad && bits > 0) out[n++] = (acc << (to - bits)) & maxv;
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    public static String encode(String hrp, byte[] data) {
        int[] d5 = regroup(data, 8, 5, true);
        int[] v = new int[expand(hrp).length + d5.length + 6];
        int[] e = expand(hrp);
        System.arraycopy(e, 0, v, 0, e.length);
        System.arraycopy(d5, 0, v, e.length, d5.length);
        int mod = polymod(v) ^ 1;
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (int x : d5) sb.append(CHARSET.charAt(x));
        for (int i = 0; i < 6; i++) sb.append(CHARSET.charAt((mod >>> (5 * (5 - i))) & 31));
        return sb.toString();
    }

    /** @return the 32 data bytes of {@code code} when it is a valid bech32 string with the given hrp, else null. */
    public static byte[] decode(String hrp, String code) {
        try {
            String s = code.trim().toLowerCase(Locale.ROOT);
            int sep = s.lastIndexOf('1');
            if (sep < 1 || !s.substring(0, sep).equals(hrp) || s.length() - sep < 7) return null;
            int[] d5 = new int[s.length() - sep - 1];
            for (int i = 0; i < d5.length; i++) {
                int idx = CHARSET.indexOf(s.charAt(sep + 1 + i));
                if (idx < 0) return null;
                d5[i] = idx;
            }
            int[] e = expand(hrp);
            int[] v = new int[e.length + d5.length];
            System.arraycopy(e, 0, v, 0, e.length);
            System.arraycopy(d5, 0, v, e.length, d5.length);
            if (polymod(v) != 1) return null;
            int acc = 0, bits = 0, n = 0;
            byte[] out = new byte[d5.length * 5 / 8];
            for (int i = 0; i < d5.length - 6; i++) {
                acc = (acc << 5) | d5[i];
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    if (n < out.length) out[n++] = (byte) ((acc >>> bits) & 0xFF);
                    acc &= (1 << bits) - 1;
                }
            }
            return n == 32 ? java.util.Arrays.copyOf(out, 32) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

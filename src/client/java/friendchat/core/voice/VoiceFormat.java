package friendchat.core.voice;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Voice note container: "FCV1", packet count (u32), then (u16 length + Opus packet)*. One packet = 20 ms at 48 kHz mono. */
public final class VoiceFormat {
    private VoiceFormat() { }

    public static final int FRAME_MS = 20;
    public static final int MAX_MS = 120_000;
    public static final String MIME = "audio/x-fcopus";

    public static byte[] pack(List<byte[]> packets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(packets.size() * 60 + 8);
        out.write('F'); out.write('C'); out.write('V'); out.write('1');
        int n = packets.size();
        out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n);
        for (byte[] p : packets) {
            out.write(p.length >>> 8);
            out.write(p.length);
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    /** @return the packets, or null when {@code d} is not a valid voice note. */
    public static List<byte[]> unpack(byte[] d) {
        if (d == null || d.length < 8 || d[0] != 'F' || d[1] != 'C' || d[2] != 'V' || d[3] != '1') return null;
        int n = ((d[4] & 0xFF) << 24) | ((d[5] & 0xFF) << 16) | ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        if (n < 0 || n > MAX_MS / FRAME_MS + 50) return null;
        List<byte[]> out = new ArrayList<>(n);
        int o = 8;
        for (int i = 0; i < n; i++) {
            if (o + 2 > d.length) return null;
            int len = ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
            o += 2;
            if (o + len > d.length) return null;
            byte[] p = new byte[len];
            System.arraycopy(d, o, p, 0, len);
            out.add(p);
            o += len;
        }
        return out;
    }

    public static int durationMs(byte[] d) {
        if (d == null || d.length < 8 || d[0] != 'F') return 0;
        int n = ((d[4] & 0xFF) << 24) | ((d[5] & 0xFF) << 16) | ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        return Math.max(0, Math.min(n, MAX_MS / FRAME_MS + 50)) * FRAME_MS;
    }
}

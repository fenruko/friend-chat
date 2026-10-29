package friendchat.core.media;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A small, dependency-free GIF87a/89a decoder (parser + LZW + disposal
 * compositing). GIF is ChatImage's animation workhorse: it needs no native
 * libraries, decodes fast, and its frames feed the in-chat player directly.
 * A header-only fast path counts frames/delays without decoding pixels.
 */
public final class GifDecoder {
    private GifDecoder() {
    }

    public record HeaderInfo(int width, int height, int frameCount, int totalDurationMs, boolean loopForever) {
    }

    /** Cheap scan used by the sniffer/validator: no pixel decoding. */
    public static HeaderInfo headerInfo(byte[] d) {
        try {
            Parsed p = parse(d, false);
            if (p == null) {
                return null;
            }
            int dur = 0;
            for (RawFrame f : p.frames) {
                dur += Math.max(10, f.delayMs);
            }
            return new HeaderInfo(p.width, p.height, p.frames.size(), dur, p.loopForever);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Full decode with disposal handling. Returns null on malformed input. */
    public static GifInfo decode(byte[] d, int maxFrames) {
        try {
            Parsed p = parse(d, true);
            if (p == null || p.frames.isEmpty()) {
                return null;
            }
            List<GifInfo.Frame> out = new ArrayList<>();
            BufferedImage canvas = new BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_ARGB);
            BufferedImage previous = null;
            int limit = Math.min(p.frames.size(), Math.max(1, maxFrames));
            for (int fi = 0; fi < limit; fi++) {
                RawFrame f = p.frames.get(fi);
                if (f.disposal == 3) {
                    previous = copy(canvas);
                }
                // draw frame pixels
                for (int y = 0; y < f.height; y++) {
                    for (int x = 0; x < f.width; x++) {
                        int idx = f.pixels[y * f.width + x] & 0xFF;
                        if (idx == f.transparentIndex) {
                            continue;
                        }
                        int[] table = f.localTable != null ? f.localTable : p.globalTable;
                        if (table == null || idx >= table.length) {
                            continue;
                        }
                        int argb = table[idx];
                        canvas.setRGB(f.left + x, f.top + y, argb);
                    }
                }
                out.add(new GifInfo.Frame(copy(canvas), Math.max(10, f.delayMs)));
                // apply disposal for next frame
                switch (f.disposal) {
                    case 2 -> {
                        for (int y = 0; y < f.height; y++) {
                            for (int x = 0; x < f.width; x++) {
                                canvas.setRGB(f.left + x, f.top + y, 0x00000000);
                            }
                        }
                    }
                    case 3 -> {
                        if (previous != null) {
                            canvas = previous;
                            previous = null;
                        }
                    }
                    default -> {
                        // 0/1: leave canvas as-is
                    }
                }
            }
            return new GifInfo(p.width, p.height, List.copyOf(out), p.loopForever);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        c.setRGB(0, 0, src.getWidth(), src.getHeight(),
                src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth()), 0, src.getWidth());
        return c;
    }

    // ---------------------------------------------------------------- parser
    private static final class RawFrame {
        int left, top, width, height, delayMs, disposal, transparentIndex = -1;
        int[] localTable;
        byte[] pixels; // palette indices, only when decoding
    }

    private static final class Parsed {
        int width, height;
        int[] globalTable;
        boolean loopForever;
        final List<RawFrame> frames = new ArrayList<>();
    }

    private static Parsed parse(byte[] d, boolean decodePixels) {
        if (d.length < 13) {
            return null;
        }
        String sig = new String(d, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
        if (!sig.equals("GIF87a") && !sig.equals("GIF89a")) {
            return null;
        }
        Parsed p = new Parsed();
        p.width = MediaSniffer.le16(d, 6);
        p.height = MediaSniffer.le16(d, 8);
        if (p.width <= 0 || p.height <= 0 || p.width > 8192 || p.height > 8192) {
            return null;
        }
        int packed = d[10] & 0xFF;
        int pos = 13;
        if ((packed & 0x80) != 0) {
            int size = 3 * (1 << ((packed & 0x07) + 1));
            p.globalTable = readTable(d, pos, size);
            if (p.globalTable == null) {
                return null;
            }
            pos += size;
        }
        int delay = 100;
        int disposal = 0;
        int transIdx = -1;
        while (pos < d.length) {
            int marker = d[pos++] & 0xFF;
            if (marker == 0x3B) { // trailer
                break;
            } else if (marker == 0x21) { // extension
                if (pos >= d.length) {
                    break;
                }
                int label = d[pos++] & 0xFF;
                if (label == 0xF9) { // graphic control
                    if (pos + 6 > d.length) {
                        break;
                    }
                    int blockSize = d[pos] & 0xFF;
                    int flags = d[pos + 1] & 0xFF;
                    disposal = (flags >> 2) & 0x07;
                    delay = MediaSniffer.le16(d, pos + 2) * 10;
                    transIdx = (flags & 0x01) != 0 ? (d[pos + 4] & 0xFF) : -1;
                    pos += 1 + blockSize + 1;
                } else if (label == 0xFF) { // application (NETSCAPE loop)
                    pos = skipSubBlocks(d, pos, p);
                } else {
                    pos = skipSubBlocks(d, pos, null);
                }
            } else if (marker == 0x2C) { // image descriptor
                if (pos + 9 > d.length) {
                    break;
                }
                RawFrame f = new RawFrame();
                f.left = MediaSniffer.le16(d, pos);
                f.top = MediaSniffer.le16(d, pos + 2);
                f.width = MediaSniffer.le16(d, pos + 4);
                f.height = MediaSniffer.le16(d, pos + 6);
                int iPacked = d[pos + 8] & 0xFF;
                pos += 9;
                if ((iPacked & 0x80) != 0) {
                    int size = 3 * (1 << ((iPacked & 0x07) + 1));
                    f.localTable = readTable(d, pos, size);
                    pos += size;
                }
                f.delayMs = delay;
                f.disposal = disposal;
                f.transparentIndex = transIdx;
                delay = 100;
                disposal = 0;
                transIdx = -1;
                if (pos >= d.length) {
                    break;
                }
                int minCode = d[pos++] & 0xFF;
                // gather compressed data
                java.io.ByteArrayOutputStream comp = new java.io.ByteArrayOutputStream();
                while (pos < d.length) {
                    int len = d[pos++] & 0xFF;
                    if (len == 0) {
                        break;
                    }
                    if (pos + len > d.length) {
                        pos = d.length;
                        break;
                    }
                    comp.write(d, pos, len);
                    pos += len;
                }
                if (decodePixels) {
                    boolean interlaced = (iPacked & 0x40) != 0;
                    byte[] indices = lzwDecode(comp.toByteArray(), minCode, f.width * f.height);
                    if (indices == null) {
                        return null;
                    }
                    f.pixels = interlaced ? deinterlace(indices, f.width, f.height) : indices;
                }
                if (f.left + f.width <= p.width && f.top + f.height <= p.height) {
                    p.frames.add(f);
                }
            } else {
                // unknown marker: bail out rather than guess
                break;
            }
        }
        return p;
    }

    private static int skipSubBlocks(byte[] d, int pos, Parsed loopTarget) {
        boolean first = true;
        while (pos < d.length) {
            int len = d[pos++] & 0xFF;
            if (len == 0) {
                break;
            }
            if (first && loopTarget != null && len == 11
                    && new String(d, pos, Math.min(11, d.length - pos), java.nio.charset.StandardCharsets.US_ASCII)
                    .startsWith("NETSCAPE")) {
                // the loop count lives in the following sub-block; peek later
                loopTarget.loopForever = true; // refined below if count present
            }
            first = false;
            if (loopTarget != null && len == 3 && pos + 2 < d.length && (d[pos] & 0xFF) == 1) {
                int count = MediaSniffer.le16(d, pos + 1);
                loopTarget.loopForever = count == 0;
            }
            pos += len;
        }
        return pos;
    }

    private static int[] readTable(byte[] d, int pos, int byteSize) {
        if (pos + byteSize > d.length) {
            return null;
        }
        int n = byteSize / 3;
        int[] table = new int[n];
        for (int i = 0; i < n; i++) {
            int r = d[pos + i * 3] & 0xFF;
            int g = d[pos + i * 3 + 1] & 0xFF;
            int b = d[pos + i * 3 + 2] & 0xFF;
            table[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return table;
    }

    private static byte[] deinterlace(byte[] src, int w, int h) {
        byte[] out = new byte[src.length];
        int[] starts = {0, 4, 2, 1};
        int[] steps = {8, 8, 4, 2};
        int srcRow = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int row = starts[pass]; row < h; row += steps[pass]) {
                if (srcRow >= h) {
                    break;
                }
                System.arraycopy(src, srcRow * w, out, row * w, Math.min(w, src.length - srcRow * w));
                srcRow++;
            }
        }
        return out;
    }

    /** Classic GIF LZW decompression. */
    private static byte[] lzwDecode(byte[] data, int minCode, int expected) {
        try {
            int clear = 1 << minCode;
            int eoi = clear + 1;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, expected));
            int[] prefix = new int[4096];
            byte[] suffix = new byte[4096];
            byte[] stack = new byte[4096];
            for (int i = 0; i < clear; i++) {
                suffix[i] = (byte) i;
            }
            int codeSize = minCode + 1;
            int nextCode = eoi + 1;
            int bitPos = 0;
            int oldCode = -1;
            int firstChar = 0;
            while (bitPos + codeSize <= data.length * 8) {
                int byteIdx = bitPos / 8;
                int bits = data[byteIdx] & 0xFF;
                if (byteIdx + 1 < data.length) {
                    bits |= (data[byteIdx + 1] & 0xFF) << 8;
                }
                if (byteIdx + 2 < data.length) {
                    bits |= (data[byteIdx + 2] & 0xFF) << 16;
                }
                int code = (bits >> (bitPos % 8)) & ((1 << codeSize) - 1);
                bitPos += codeSize;
                if (code == clear) {
                    codeSize = minCode + 1;
                    nextCode = eoi + 1;
                    oldCode = -1;
                    continue;
                }
                if (code == eoi) {
                    break;
                }
                int inCode = code;
                int top = 0;
                if (code > nextCode) {
                    return null;
                }
                if (oldCode >= 0 && code == nextCode) {
                    stack[top++] = (byte) firstChar;
                    code = oldCode;
                }
                while (code > clear) {
                    if (top >= stack.length || code >= prefix.length) {
                        return null;
                    }
                    stack[top++] = suffix[code];
                    code = prefix[code];
                }
                firstChar = suffix[code] & 0xFF;
                stack[top++] = (byte) firstChar;
                while (top > 0) {
                    out.write(stack[--top]);
                }
                if (oldCode >= 0 && nextCode < 4096) {
                    prefix[nextCode] = oldCode;
                    suffix[nextCode] = (byte) firstChar;
                    nextCode++;
                    if (nextCode >= (1 << codeSize) && codeSize < 12) {
                        codeSize++;
                    }
                }
                oldCode = inCode;
                if (out.size() > expected + 4096) {
                    return null; // decompression bomb guard
                }
            }
            byte[] result = out.toByteArray();
            if (result.length < expected) {
                byte[] padded = new byte[expected];
                System.arraycopy(result, 0, padded, 0, result.length);
                return padded;
            }
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package friendchat.core.media;



import java.nio.charset.StandardCharsets;

/**
 * Identifies media by magic bytes only - file extensions are never trusted.
 * Returns container facts (size / duration / frames) when cheaply readable.
 */
public final class MediaSniffer {
    private MediaSniffer() {
    }

    public record Result(MediaKind kind, String mime, int width, int height,
                         int durationMs, int frameCount) {
    }

    public static Result sniff(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        // PNG
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            int w = d.length >= 24 ? be32(d, 16) : -1;
            int h = d.length >= 24 ? be32(d, 20) : -1;
            return new Result(MediaKind.IMAGE, "image/png", w, h, 0, 1);
        }
        // JPEG
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int[] wh = jpegSize(d);
            return new Result(MediaKind.IMAGE, "image/jpeg", wh[0], wh[1], 0, 1);
        }
        // GIF
        String head6 = new String(d, 0, 6, StandardCharsets.US_ASCII);
        if (head6.equals("GIF87a") || head6.equals("GIF89a")) {
            int w = le16(d, 6);
            int h = le16(d, 8);
            GifDecoder.HeaderInfo info = GifDecoder.headerInfo(d);
            int frames = info == null ? 1 : info.frameCount();
            int dur = info == null ? 0 : info.totalDurationMs();
            MediaKind kind = frames > 1 ? MediaKind.GIF : MediaKind.IMAGE;
            return new Result(kind, "image/gif", w, h, dur, frames);
        }
        // WebP
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return new Result(MediaKind.IMAGE, "image/webp", -1, -1, 0, 1);
        }
        // BMP
        if (d[0] == 'B' && d[1] == 'M') {
            return new Result(MediaKind.IMAGE, "image/bmp", le32(d, 18), Math.abs(le32(d, 22)), 0, 1);
        }
        // MP4 / MOV (ftyp)
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            VideoInfo v = VideoInfo.parseMp4(d);
            return new Result(MediaKind.VIDEO, "video/mp4",
                    v == null ? -1 : v.width(), v == null ? -1 : v.height(),
                    v == null ? 0 : v.durationMs(), 0);
        }
        // WebM / Matroska (EBML)
        if ((d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45 && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) {
            return new Result(MediaKind.VIDEO, "video/webm", -1, -1, 0, 0);
        }
        // AVI
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'A' && d[9] == 'V' && d[10] == 'I') {
            return new Result(MediaKind.VIDEO, "video/avi", -1, -1, 0, 0);
        }
        return null;
    }

    public static MediaKind kindForFileName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".gif")) {
            return MediaKind.GIF;
        }
        if (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".mkv")) {
            return MediaKind.VIDEO;
        }
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".bmp")) {
            return MediaKind.IMAGE;
        }
        return null;
    }

    static int be32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    static int le16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static int[] jpegSize(byte[] d) {
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            int len = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
            if (len < 2) {
                break;
            }
            i += 2 + len;
        }
        return new int[]{-1, -1};
    }
}

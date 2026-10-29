package friendchat.core.media;

import java.security.MessageDigest;
import java.util.HexFormat;

/** One outgoing file, fully sniffed/scaled/hashed and ready to encrypt + upload. */
public record PreparedAttachment(MediaKind kind, String fileName, String mime, byte[] bytes, String sha256Hex,
                                 int width, int height, int durationMs, int frameCount) {

    public static PreparedAttachment of(MediaKind kind, String fileName, String mime, byte[] bytes,
                                        int width, int height, int durationMs, int frameCount) {
        return new PreparedAttachment(kind, sanitizeName(fileName), mime, bytes, sha256Hex(bytes),
                width, height, durationMs, frameCount);
    }

    public static String sanitizeName(String name) {
        if (name == null) return "media";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\x00-\\x1F\\x7F]", "_").trim();
        if (n.isEmpty()) n = "media";
        return n.substring(0, Math.min(n.length(), 96));
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int size() {
        return bytes == null ? 0 : bytes.length;
    }
}

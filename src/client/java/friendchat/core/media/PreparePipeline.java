package friendchat.core.media;









import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Turns a picked file into a send-ready attachment: sniff the real type,
 * downscale stills that are too large (dimension or bytes), read GIF/video
 * facts. Every rejection carries a player-readable reason. Pure logic - the
 * file picker in each loader only supplies bytes + a display name.
 */
public final class PreparePipeline {
    private PreparePipeline() {
    }

    public sealed interface Outcome {
        record Ok(PreparedAttachment attachment) implements Outcome {
        }

        record Rejected(String reason) implements Outcome {
        }
    }

    public static Outcome prepare(String fileName, byte[] bytes, Limits limits) {
        if (bytes == null || bytes.length == 0) {
            return new Outcome.Rejected("That file is empty.");
        }
        MediaSniffer.Result sniff = MediaSniffer.sniff(bytes);
        if (sniff == null) {
            return new Outcome.Rejected("Not an image, GIF or video file.");
        }
        int maxFile = limits == null ? 8 * 1024 * 1024 : limits.maxFileBytes;

        switch (sniff.kind()) {
            case IMAGE -> {
                if (limits != null && !limits.allowImages) {
                    return new Outcome.Rejected("This server does not allow images.");
                }
                BufferedImage img = ImageCodec.decode(bytes);
                if (img == null) {
                    // e.g. WebP: JDK has no decoder. Relay only if small enough;
                    // receivers without a decoder show a tidy placeholder card.
                    if (bytes.length > maxFile) {
                        return new Outcome.Rejected("That image is too large for this server.");
                    }
                    return new Outcome.Ok(PreparedAttachment.of(MediaKind.IMAGE, fileName, sniff.mime(),
                            bytes, sniff.width(), sniff.height(), 0, 1));
                }
                int maxDim = limits == null ? 1024 : limits.maxImageDimension;
                byte[] send = bytes;
                BufferedImage scaled = ImageCodec.fit(img, maxDim);
                if (scaled != img || bytes.length > maxFile) {
                    boolean png = "image/png".equals(sniff.mime());
                    send = png ? ImageCodec.encodePng(scaled) : ImageCodec.encodeJpeg(scaled);
                    if (send == null) {
                        send = bytes;
                    }
                    img = scaled;
                }
                if (send.length > maxFile) {
                    // last resort: shrink harder and use JPEG
                    BufferedImage smaller = ImageCodec.fit(img, Math.max(128, maxDim / 2));
                    byte[] jpg = ImageCodec.encodeJpeg(smaller);
                    if (jpg != null && jpg.length < send.length) {
                        send = jpg;
                        img = smaller;
                    }
                }
                if (send.length > maxFile) {
                    return new Outcome.Rejected("That image is too large for this server (max "
                            + human(maxFile) + ").");
                }
                return new Outcome.Ok(PreparedAttachment.of(MediaKind.IMAGE, fileName, sniff.mime(),
                        send, img.getWidth(), img.getHeight(), 0, 1));
            }
            case GIF -> {
                if (limits != null && !limits.allowGifs) {
                    return new Outcome.Rejected("This server does not allow GIFs.");
                }
                if (bytes.length > maxFile) {
                    return new Outcome.Rejected("That GIF is too large for this server (max "
                            + human(maxFile) + ").");
                }
                GifInfo info = GifDecoder.decode(bytes, limits == null ? 400 : limits.maxGifFrames);
                int frames = info == null ? sniff.frameCount() : info.frameCount();
                int dur = info == null ? sniff.durationMs() : info.totalDurationMs();
                int w = info == null ? sniff.width() : info.width();
                int h = info == null ? sniff.height() : info.height();
                MediaKind kind = frames > 1 ? MediaKind.GIF : MediaKind.IMAGE;
                return new Outcome.Ok(PreparedAttachment.of(kind, fileName, "image/gif",
                        bytes, w, h, dur, frames));
            }
            case VIDEO -> {
                if (limits != null && !limits.allowVideos) {
                    return new Outcome.Rejected("This server does not allow videos.");
                }
                if (bytes.length > maxFile) {
                    return new Outcome.Rejected("That video is too large for this server (max "
                            + human(maxFile) + ").");
                }
                int dur = sniff.durationMs();
                int w = sniff.width();
                int h = sniff.height();
                if (dur == 0 && "video/mp4".equals(sniff.mime())) {
                    VideoInfo v = VideoInfo.parseMp4(bytes);
                    if (v != null) {
                        dur = v.durationMs();
                        w = v.width();
                        h = v.height();
                    }
                }
                if (limits != null && dur > limits.maxVideoSeconds * 1000L) {
                    return new Outcome.Rejected("That video is longer than this server allows ("
                            + limits.maxVideoSeconds + "s).");
                }
                return new Outcome.Ok(PreparedAttachment.of(MediaKind.VIDEO, fileName, sniff.mime(),
                        bytes, w, h, dur, 0));
            }
        }
        return new Outcome.Rejected("Unsupported file.");
    }

    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

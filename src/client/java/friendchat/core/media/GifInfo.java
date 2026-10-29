package friendchat.core.media;

import java.awt.image.BufferedImage;
import java.util.List;

/** A fully decoded GIF: composited ARGB frames plus per-frame delays. */
public record GifInfo(int width, int height, List<Frame> frames, boolean loopForever) {

    public record Frame(BufferedImage image, int delayMs) {
    }

    public int frameCount() {
        return frames.size();
    }

    public int totalDurationMs() {
        int total = 0;
        for (Frame f : frames) {
            total += Math.max(10, f.delayMs());
        }
        return total;
    }
}

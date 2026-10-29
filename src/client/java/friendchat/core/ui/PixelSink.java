package friendchat.core.ui;

import java.awt.image.BufferedImage;

/** Where the UI draws. In-game this is the GUI; in tests it is a BufferedImage. Coordinates are GUI pixels. */
public interface PixelSink {
    /** Fills [x0,x1) x [y0,y1) with an ARGB colour. */
    void fill(int x0, int y0, int x1, int y1, int argb);

    /** Draws text with the host's own font (message bodies, names in any script), clipped to x in [clipX0, clipX1). */
    void text(String s, int x, int y, int argb, int clipX0, int clipX1);

    /** Width in GUI pixels of {@code s} in the host font. */
    int textWidth(String s);

    /**
     * Draws a picture filling [x0,x1) x [y0,y1). {@code key} identifies the picture (the host keeps one texture per
     * key and re-uploads only when {@code img} for that key is a different object); null img draws nothing.
     */
    void image(String key, BufferedImage img, int x0, int y0, int x1, int y1);
}

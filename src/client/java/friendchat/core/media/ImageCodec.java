package friendchat.core.media;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Still-image helpers built only on the JDK (PNG/JPEG/BMP/GIF stills). */
public final class ImageCodec {
    private ImageCodec() {
    }

    public static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Downscale (never upscale) so the longest edge fits maxDimension. */
    public static BufferedImage fit(BufferedImage img, int maxDimension) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDimension || longest == 0) {
            return img;
        }
        double scale = (double) maxDimension / longest;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    public static byte[] encodePng(BufferedImage img) {
        return encode(img, "png");
    }

    public static byte[] encodeJpeg(BufferedImage img) {
        // JPEG has no alpha: flatten onto white first.
        BufferedImage flat = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return encode(flat, "jpg");
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(img, format, out)) {
                return null;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** ARGB pixels, row-major - the lingua franca with loader texture code. */
    public static int[] toArgb(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    public static BufferedImage fromArgb(int width, int height, int[] argb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        return img;
    }
}

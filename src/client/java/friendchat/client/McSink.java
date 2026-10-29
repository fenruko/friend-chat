package friendchat.client;

import com.mojang.blaze3d.platform.NativeImage;
import friendchat.core.ui.PixelSink;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Adapter between the Minecraft-free UI and the game's GUI (same role as PixelTunes' McSink). Picture textures are
 * uploaded once per picture and released when they have not been drawn for a while, so memory stays flat.
 */
final class McSink implements PixelSink {
    private static final int MAX_TEXTURES = 16;

    private GuiGraphicsExtractor g;
    private Font font;

    private static final class Tex {
        final Identifier id;
        BufferedImage source;
        long lastUsed;
        boolean usable;

        Tex(Identifier id) { this.id = id; }
    }

    private final Map<String, Tex> textures = new HashMap<>();
    private long frame;

    void begin(GuiGraphicsExtractor graphics, Font f) {
        this.g = graphics;
        this.font = f;
        frame++;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (x1 > x0 && y1 > y0) g.fill(x0, y0, x1, y1, argb);
    }

    @Override
    public void text(String s, int x, int y, int argb, int clipX0, int clipX1) {
        if (clipX1 <= clipX0) return;
        g.enableScissor(clipX0, y - 1, clipX1, y + 11);
        g.text(font, s, x, y, argb, false);
        g.disableScissor();
    }

    @Override
    public int textWidth(String s) {
        return font.width(s);
    }

    @Override
    public void image(String key, BufferedImage img, int x0, int y0, int x1, int y1) {
        if (img == null) return;
        Tex t = textures.get(key);
        if (t == null) {
            evict();
            t = new Tex(Identifier.fromNamespaceAndPath("friendchat", "pic/" + Integer.toHexString(key.hashCode()) + "_" + textures.size() + "_" + (frame & 0xFFFF)));
            textures.put(key, t);
        }
        t.lastUsed = frame;
        if (t.source != img) {
            t.source = img;
            t.usable = upload(t);
        }
        if (t.usable) g.blit(t.id, x0, y0, x1, y1, 0f, 1f, 0f, 1f);
    }

    private boolean upload(Tex t) {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream(64 * 1024);
            ImageIO.write(t.source, "png", png);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
            Minecraft mc = Minecraft.getInstance();
            mc.getTextureManager().release(t.id);
            mc.getTextureManager().register(t.id, new DynamicTexture(() -> "friendchat picture", image));
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[friend-chat] could not upload picture: " + e);
            return false;
        }
    }

    private void evict() {
        if (textures.size() < MAX_TEXTURES) return;
        List<Map.Entry<String, Tex>> all = new ArrayList<>(textures.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue().lastUsed, b.getValue().lastUsed));
        for (int i = 0; i < all.size() / 2; i++) {
            release(all.get(i).getValue());
            textures.remove(all.get(i).getKey());
        }
    }

    private static void release(Tex t) {
        try {
            Minecraft.getInstance().getTextureManager().release(t.id);
        } catch (RuntimeException ignored) {
            // game is tearing down
        }
    }

    /** Frees everything (screen closed / game stopping). */
    void close() {
        Iterator<Tex> it = textures.values().iterator();
        while (it.hasNext()) {
            release(it.next());
            it.remove();
        }
    }
}

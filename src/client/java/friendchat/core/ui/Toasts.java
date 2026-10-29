package friendchat.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Pixel-art toast notifications. Any thread may push; only the render thread draws. Nothing is allocated per frame. */
public final class Toasts {
    public static final int INFO = 0, MESSAGE = 1, ERROR = 2;
    private static final long LIFE_MS = 4500, SLIDE_MS = 260;
    private static final int MAX = 3;

    private record Toast(Runs title, String titleRaw, String body, int kind, long born) { }

    private final ConcurrentLinkedQueue<Toast> incoming = new ConcurrentLinkedQueue<>();
    private final List<Toast> active = new ArrayList<>(MAX);
    private volatile boolean enabled = true;

    public void setEnabled(boolean on) { enabled = on; }

    public void push(String title, String body, int kind) {
        if (!enabled && kind != ERROR) return;
        if (incoming.size() > 12) return; // never let a flood of messages pile up
        incoming.add(new Toast(PixelFont.layout(title.length() > 22 ? title.substring(0, 22) : title), title, body, kind, 0));
    }

    public void render(PixelSink g, Theme t, int sw, int sh, int p, long nowMs) {
        Toast in;
        while (active.size() < MAX && (in = incoming.poll()) != null) active.add(new Toast(in.title, in.titleRaw, in.body, in.kind, nowMs));
        if (active.isEmpty()) return;
        int w = Math.min(sw - 8, 118 * p), h = 24 * p, y = 4 + (sh > 0 ? 0 : 0);
        for (int i = 0; i < active.size(); i++) {
            Toast x = active.get(i);
            long age = nowMs - x.born;
            if (age > LIFE_MS) {
                active.remove(i--);
                continue;
            }
            double in01 = Math.min(1.0, age / (double) SLIDE_MS), out01 = Math.min(1.0, (LIFE_MS - age) / (double) SLIDE_MS);
            double slide = Math.min(in01, out01);
            int left = sw - 4 - w + (int) Math.round((1 - slide) * (w + 8));
            draw(g, t, x, left, y, w, h, p);
            y += h + 3 * p;
        }
    }

    private static void draw(PixelSink g, Theme t, Toast x, int l, int y, int w, int h, int p) {
        int accent = x.kind == ERROR ? 0xFFE0544A : x.kind == MESSAGE ? t.acc : t.amb;
        g.fill(l, y + p, l + w, y + h - p, t.out);
        g.fill(l + p, y, l + w - p, y + h, t.out);
        g.fill(l + p, y + p, l + w - p, y + h - p, t.bg);
        g.fill(l + p, y + p, l + w - p, y + 2 * p, t.bgHi);
        g.fill(l + p, y + p, l + 3 * p, y + h - p, accent); // accent stripe
        int tx = l + 5 * p;
        if (x.title.fallback) g.text(x.titleRaw, tx, y + 3 * p, t.txt, tx, l + w - 3 * p);
        else for (int i = 0; i < x.title.data.length; i += 3)
            g.fill(tx + x.title.data[i] * p, y + 4 * p + x.title.data[i + 1] * p, tx + (x.title.data[i] + x.title.data[i + 2]) * p, y + 5 * p + x.title.data[i + 1] * p, accent);
        String body = x.body;
        int max = w - 8 * p;
        while (body.length() > 3 && g.textWidth(body) > max) body = body.substring(0, body.length() - 2);
        if (body != x.body) body = body.stripTrailing() + "…";
        g.text(body, tx, y + 12 * p, t.dim, tx, l + w - 3 * p);
    }
}

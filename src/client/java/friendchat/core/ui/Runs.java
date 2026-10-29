package friendchat.core.ui;

import java.util.ArrayList;
import java.util.List;

/** A bitmap stored as horizontal runs: (x, y, length) triples. Immutable. */
public final class Runs {
    public static final Runs EMPTY = new Runs(0, 0, new int[0], false, "");

    public final int w, h;
    public final int[] data;
    /** True if the text contained characters the pixel font cannot draw. */
    public final boolean fallback;
    public final String source;

    public Runs(int w, int h, int[] data, boolean fallback, String source) {
        this.w = w;
        this.h = h;
        this.data = data;
        this.fallback = fallback;
        this.source = source;
    }

    /** Builds runs from rows of '#' (set) and anything else (clear). */
    public static Runs ofRows(String... rows) {
        List<Integer> out = new ArrayList<>();
        int w = 0;
        for (int y = 0; y < rows.length; y++) {
            String r = rows[y];
            w = Math.max(w, r.length());
            for (int x = 0; x < r.length(); ) {
                if (r.charAt(x) != '#') {
                    x++;
                    continue;
                }
                int s = x;
                while (x < r.length() && r.charAt(x) == '#') x++;
                out.add(s);
                out.add(y);
                out.add(x - s);
            }
        }
        int[] d = new int[out.size()];
        for (int i = 0; i < d.length; i++) d[i] = out.get(i);
        return new Runs(w, rows.length, d, false, "");
    }
}

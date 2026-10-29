package friendchat.core.ui;

import java.util.Locale;

/**
 * Every colour the widget uses, derived from four base colours (window, title bar, accent, text) so that any
 * custom choice still gets proper hand-drawn bevels, outlines and dimmed text.
 */
public final class Theme {
    public static final String[] PRESETS = {"PURPLE", "FOREST", "OCEAN", "SUNSET", "ROSE", "MINT", "MONO", "CREAM"};
    public static final String CUSTOM = "CUSTOM";

    private static final int[][] BASES = {
            // window,   title bar, accent,   text
            {0x241A35, 0x52308F, 0x5BE37D, 0xF4ECD8}, // PURPLE (the original look)
            {0x15241B, 0x2E6B45, 0xB7E36B, 0xEEF3DC}, // FOREST
            {0x102033, 0x1F5FA8, 0x4FD6E8, 0xE6F2FA}, // OCEAN
            {0x33182B, 0xB0442F, 0xFFB347, 0xFFF0DC}, // SUNSET
            {0x3A1B2E, 0x9C3D6E, 0xFF8FB8, 0xFFEFF4}, // ROSE
            {0x12191A, 0x1A7F45, 0x3DDC84, 0xF2FFF6}, // MINT
            {0x1E1E1E, 0x4A4A4A, 0xE0E0E0, 0xF0F0F0}, // MONO
            {0xE8DCC0, 0xC9A66B, 0x3E8E5A, 0x2A2118}, // CREAM (light)
    };

    public final int out, bg, bgHi, bgLo, bar, barHi, barLo, txt, dim, muted;
    public final int btn, btnHi, btnLo, btnHov, btnPress;
    public final int acc, accHi, accDk, amb, ambHi, ambDk;
    public final int track, phBg, phFg;
    public final int baseBg, baseBar, baseAccent, baseText;

    private Theme(int bgC, int barC, int accC, int txtC) {
        baseBg = bgC & 0xFFFFFF;
        baseBar = barC & 0xFFFFFF;
        baseAccent = accC & 0xFFFFFF;
        baseText = txtC & 0xFFFFFF;
        bg = opaque(bgC);
        out = mix(bg, 0xFF000000, 0.62f);
        bgHi = mix(bg, 0xFFFFFFFF, 0.14f);
        bgLo = mix(bg, 0xFF000000, 0.35f);
        bar = opaque(barC);
        barHi = mix(bar, 0xFFFFFFFF, 0.22f);
        barLo = mix(bar, 0xFF000000, 0.30f);
        txt = opaque(txtC);
        dim = mix(txt, bg, 0.40f);
        muted = mix(txt, bg, 0.68f);
        btn = bgHi;
        btnHi = mix(btn, 0xFFFFFFFF, 0.14f);
        btnLo = mix(btn, 0xFF000000, 0.30f);
        btnHov = mix(btn, 0xFFFFFFFF, 0.08f);
        btnPress = mix(btn, 0xFF000000, 0.45f);
        acc = opaque(accC);
        accHi = mix(acc, 0xFFFFFFFF, 0.45f);
        accDk = mix(acc, 0xFF000000, 0.35f);
        amb = 0xFFF2B63D;
        ambHi = 0xFFFFD98A;
        ambDk = 0xFFB07A18;
        track = mix(bg, 0xFF000000, 0.42f);
        phBg = mix(bg, bar, 0.35f);
        phFg = mix(bar, txt, 0.30f);
    }

    public static Theme of(int bg, int bar, int accent, int text) {
        return new Theme(bg, bar, accent, text);
    }

    /** Preset by name (case-insensitive); unknown names give the original purple. */
    public static Theme preset(String name) {
        int i = presetIndex(name);
        int[] b = BASES[i < 0 ? 0 : i];
        return new Theme(b[0], b[1], b[2], b[3]);
    }

    public static int[] presetColors(String name) {
        int i = presetIndex(name);
        return BASES[i < 0 ? 0 : i].clone();
    }

    public static int presetIndex(String name) {
        if (name == null) return -1;
        String n = name.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i].equals(n)) return i;
        return -1;
    }

    private static int opaque(int c) {
        return 0xFF000000 | (c & 0xFFFFFF);
    }

    /** Linear mix of two ARGB colours; t=0 gives a, t=1 gives b. Result is opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Relative luminance 0..1 (sRGB approximation), used for contrast checks. */
    public static double luminance(int c) {
        double r = lin((c >> 16) & 255), g = lin((c >> 8) & 255), b = lin(c & 255);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** "#RRGGBB" */
    public static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** Parses "#RRGGBB", "RRGGBB" or "0xRRGGBB"; returns fallback on anything else. */
    public static int parseHex(String s, int fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.regionMatches(true, 0, "0x", 0, 2)) t = t.substring(2);
        if (t.length() != 6) return fallback;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

package friendchat.core.ui;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Hand-made 3x5 pixel font. Upper-case only; accents are folded away; unknown scripts report fallback. */
public final class PixelFont {
    public static final int HEIGHT = 5;
    private static final int SPACE_W = 2;
    private static final int MAX_CHARS = 160;

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void g(char c, String... rows) {
        if (rows.length != HEIGHT) throw new IllegalStateException("glyph height " + c);
        for (String r : rows) if (r.length() != rows[0].length()) throw new IllegalStateException("glyph width " + c);
        GLYPHS.put(c, rows);
    }

    static {
        g('A', ".#.", "#.#", "###", "#.#", "#.#");
        g('B', "##.", "#.#", "##.", "#.#", "##.");
        g('C', ".##", "#..", "#..", "#..", ".##");
        g('D', "##.", "#.#", "#.#", "#.#", "##.");
        g('E', "###", "#..", "##.", "#..", "###");
        g('F', "###", "#..", "##.", "#..", "#..");
        g('G', ".##", "#..", "#.#", "#.#", ".##");
        g('H', "#.#", "#.#", "###", "#.#", "#.#");
        g('I', "###", ".#.", ".#.", ".#.", "###");
        g('J', "..#", "..#", "..#", "#.#", ".#.");
        g('K', "#.#", "#.#", "##.", "#.#", "#.#");
        g('L', "#..", "#..", "#..", "#..", "###");
        g('M', "#.#", "###", "###", "#.#", "#.#");
        g('N', "##.", "#.#", "#.#", "#.#", "#.#");
        g('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        g('P', "##.", "#.#", "##.", "#..", "#..");
        g('Q', ".#.", "#.#", "#.#", "##.", ".##");
        g('R', "##.", "#.#", "##.", "#.#", "#.#");
        g('S', ".##", "#..", ".#.", "..#", "##.");
        g('T', "###", ".#.", ".#.", ".#.", ".#.");
        g('U', "#.#", "#.#", "#.#", "#.#", "###");
        g('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        g('W', "#.#", "#.#", "###", "###", "#.#");
        g('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        g('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        g('Z', "###", "..#", ".#.", "#..", "###");
        g('0', "###", "#.#", "#.#", "#.#", "###");
        g('1', ".#.", "##.", ".#.", ".#.", "###");
        g('2', "##.", "..#", ".#.", "#..", "###");
        g('3', "##.", "..#", ".#.", "..#", "##.");
        g('4', "#.#", "#.#", "###", "..#", "..#");
        g('5', "###", "#..", "##.", "..#", "##.");
        g('6', ".##", "#..", "###", "#.#", "###");
        g('7', "###", "..#", ".#.", ".#.", ".#.");
        g('8', "###", "#.#", "###", "#.#", "###");
        g('9', "###", "#.#", "###", "..#", "##.");
        g('.', ".", ".", ".", ".", "#");
        g(',', "..", "..", "..", ".#", "#.");
        g(':', ".", "#", ".", "#", ".");
        g(';', ".", "#", ".", "#", "#");
        g('!', "#", "#", "#", ".", "#");
        g('?', "##.", "..#", ".#.", "...", ".#.");
        g('\'', "#", "#", ".", ".", ".");
        g('"', "#.#", "#.#", "...", "...", "...");
        g('-', "...", "...", "###", "...", "...");
        g('_', "...", "...", "...", "...", "###");
        g('+', "...", ".#.", "###", ".#.", "...");
        g('=', "...", "###", "...", "###", "...");
        g('/', "..#", "..#", ".#.", "#..", "#..");
        g('\\', "#..", "#..", ".#.", "..#", "..#");
        g('(', ".#", "#.", "#.", "#.", ".#");
        g(')', "#.", ".#", ".#", ".#", "#.");
        g('[', "##", "#.", "#.", "#.", "##");
        g(']', "##", ".#", ".#", ".#", "##");
        g('<', "..#", ".#.", "#..", ".#.", "..#");
        g('>', "#..", ".#.", "..#", ".#.", "#..");
        g('&', ".#.", "#.#", ".##", "#.#", ".##");
        g('*', "...", "#.#", ".#.", "#.#", "...");
        g('#', "#.#", "###", "#.#", "###", "#.#");
        g('@', ".##", "#.#", "###", "#..", ".##");
        g('%', "#.#", "..#", ".#.", "#..", "#.#");
        g('$', ".##", "##.", ".#.", ".##", "##.");
        g('|', "#", "#", "#", "#", "#");
        g('~', "...", ".##", "##.", "...", "...");
        g('^', ".#.", "#.#", "...", "...", "...");
    }

    private PixelFont() { }

    /** Folds text into what the font can draw. */
    public static String normalise(String s) {
        if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '‘', '’', 'ʼ', '´', '`' -> b.append('\'');
                case '“', '”', '«', '»' -> b.append('"');
                case '–', '—', '−', '‐', '‑' -> b.append('-');
                case '…' -> b.append("...");
                case '×' -> b.append('x');
                case ' ', '\t', '\n', '\r' -> b.append(' ');
                case 'ß' -> b.append("SS");
                case 'Æ', 'æ' -> b.append("AE");
                case 'Œ', 'œ' -> b.append("OE");
                case 'Ø', 'ø' -> b.append('O');
                case 'Ł', 'ł' -> b.append('L');
                case 'Đ', 'đ', 'Ð', 'ð' -> b.append('D');
                default -> b.append(c);
            }
        }
        String d = Normalizer.normalize(b, Normalizer.Form.NFD);
        StringBuilder o = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            o.append(c);
        }
        return o.toString().toUpperCase(Locale.ROOT).strip();
    }

    public static boolean supports(char c) {
        return c == ' ' || GLYPHS.containsKey(c);
    }

    /** Lays out a string. {@code fallback} is set if some character needs a different font. */
    public static Runs layout(String raw) {
        String s = normalise(raw);
        if (s.isEmpty()) return Runs.EMPTY;
        boolean fallback = false;
        for (int i = 0; i < s.length(); i++) {
            if (!supports(s.charAt(i))) {
                fallback = true;
                break;
            }
        }
        if (fallback) return new Runs(0, 0, new int[0], true, raw);

        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            String[] gl = GLYPHS.get(s.charAt(i));
            if (gl != null) count += gl[0].length() * HEIGHT; // upper bound on run count
        }
        int[] data = new int[count * 3];
        int n = 0, x = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                x += SPACE_W + 1;
                continue;
            }
            String[] gl = GLYPHS.get(c);
            for (int y = 0; y < HEIGHT; y++) {
                String row = gl[y];
                for (int k = 0; k < row.length(); ) {
                    if (row.charAt(k) != '#') {
                        k++;
                        continue;
                    }
                    int st = k;
                    while (k < row.length() && row.charAt(k) == '#') k++;
                    data[n++] = x + st;
                    data[n++] = y;
                    data[n++] = k - st;
                }
            }
            x += gl[0].length() + 1;
        }
        int w = Math.max(0, x - 1);
        int[] out = new int[n];
        System.arraycopy(data, 0, out, 0, n);
        return new Runs(w, HEIGHT, out, false, raw);
    }
}

package friendchat.core;

/** Minimal assertion helper (no test framework needed, same idea as PixelTunes' tests). */
public final class T {
    public static int passed, failed;

    public static void eq(String name, Object expected, Object actual) {
        if (java.util.Objects.equals(expected, actual)) passed++;
        else {
            failed++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> but got <" + actual + ">");
        }
    }

    public static void ok(String name, boolean cond) { eq(name, true, cond); }

    public static void done(String suite) {
        System.out.println(suite + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}

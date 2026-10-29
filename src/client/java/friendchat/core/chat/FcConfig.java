package friendchat.core.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Plain .properties settings. Relays and storage servers are public infrastructure and can be swapped freely. */
public final class FcConfig {
    public String name = "";
    public String relays = "wss://relay.damus.io,wss://nos.lol,wss://relay.primal.net";
    public String blossom = "https://blossom.primal.net,https://cdn.hzrd149.com";
    public String theme = "PURPLE";
    public int pixelSize = 1;
    public boolean toasts = true;
    public int maxFileMb = 8;

    public List<String> relayList() { return split(relays, "ws"); }

    public List<String> blossomList() { return split(blossom, "http"); }

    private static List<String> split(String s, String scheme) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[,\\s]+")) if (p.startsWith(scheme)) out.add(p);
        return out;
    }

    public static FcConfig load(Path file) {
        FcConfig c = new FcConfig();
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return c; // first run: defaults
        }
        c.name = p.getProperty("name", c.name);
        c.relays = p.getProperty("relays", c.relays);
        c.blossom = p.getProperty("blossom", c.blossom);
        c.theme = p.getProperty("theme", c.theme);
        c.toasts = !"false".equalsIgnoreCase(p.getProperty("toasts", "true"));
        c.pixelSize = clamp(p.getProperty("pixelSize"), 1, 4, c.pixelSize);
        c.maxFileMb = clamp(p.getProperty("maxFileMb"), 1, 32, c.maxFileMb);
        return c;
    }

    private static int clamp(String s, int lo, int hi, int def) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(s.trim())));
        } catch (RuntimeException e) {
            return def;
        }
    }

    public void save(Path file) {
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("relays", relays);
        p.setProperty("blossom", blossom);
        p.setProperty("theme", theme);
        p.setProperty("pixelSize", Integer.toString(pixelSize));
        p.setProperty("toasts", Boolean.toString(toasts));
        p.setProperty("maxFileMb", Integer.toString(maxFileMb));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "friend-chat settings. relays/blossom are comma-separated public servers.");
            }
        } catch (IOException ignored) {
            // read-only config dir: settings simply do not persist
        }
    }
}

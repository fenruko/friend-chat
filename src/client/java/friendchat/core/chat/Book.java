package friendchat.core.chat;

import friendchat.core.chat.Models.Friend;
import friendchat.core.chat.Models.Group;
import friendchat.core.chat.Models.Msg;
import friendchat.core.nostr.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Friends, groups, requests and the per-conversation history files. Only the engine thread touches it. */
public final class Book {
    public final Map<String, Friend> friends = new LinkedHashMap<>();
    public final Map<String, Group> groups = new LinkedHashMap<>();
    public final Map<String, String> requests = new LinkedHashMap<>();
    public final Set<String> blocked = new LinkedHashSet<>();
    public long lastSeen;

    private final Path dir;
    private boolean dirty;

    public Book(Path dir) { this.dir = dir; }

    public void load() {
        try {
            Map<String, Object> o = Json.obj(Json.parse(Files.readString(dir.resolve("book.json"))));
            if (o == null) return;
            lastSeen = Json.num(o, "lastSeen", 0);
            List<Object> f = Json.arr(o.get("friends"));
            if (f != null) for (Object x : f) {
                Map<String, Object> m = Json.obj(x);
                String pk = Json.str(m, "pk", "");
                if (pk.matches("[0-9a-f]{64}")) friends.put(pk, new Friend(pk, Models.clean(Json.str(m, "name", ""), 24)));
            }
            List<Object> g = Json.arr(o.get("groups"));
            if (g != null) for (Object x : g) {
                Group gr = Group.fromMap(Json.obj(x));
                if (gr != null) groups.put(gr.id(), gr);
            }
            List<Object> r = Json.arr(o.get("requests"));
            if (r != null) for (Object x : r) {
                Map<String, Object> m = Json.obj(x);
                String pk = Json.str(m, "pk", "");
                if (pk.matches("[0-9a-f]{64}")) requests.put(pk, Models.clean(Json.str(m, "name", ""), 24));
            }
            List<Object> b = Json.arr(o.get("blocked"));
            if (b != null) for (Object x : b) if (x instanceof String s && s.matches("[0-9a-f]{64}")) blocked.add(s);
        } catch (IOException | RuntimeException e) {
            // first run or unreadable file: start empty
        }
    }

    public void markDirty() { dirty = true; }

    public void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("lastSeen", lastSeen);
        List<Object> f = new ArrayList<>();
        for (Friend x : friends.values()) f.add(Map.of("pk", x.hex(), "name", x.name()));
        o.put("friends", f);
        List<Object> g = new ArrayList<>();
        for (Group x : groups.values()) g.add(x.toMap());
        o.put("groups", g);
        List<Object> r = new ArrayList<>();
        for (Map.Entry<String, String> x : requests.entrySet()) r.add(Map.of("pk", x.getKey(), "name", x.getValue()));
        o.put("requests", r);
        o.put("blocked", new ArrayList<>(blocked));
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("book.json.tmp");
            Files.writeString(tmp, Json.write(o));
            Files.move(tmp, dir.resolve("book.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            dirty = true; // try again next tick
        }
    }

    // ---- history
    private Path historyFile(String convKey) {
        return dir.resolve("history").resolve(convKey.replace(':', '_') + ".jsonl");
    }

    public List<Msg> loadHistory(String convKey, int limit) {
        List<Msg> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(historyFile(convKey), StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - limit); i < lines.size(); i++) {
                Msg m = Msg.fromMap(Json.obj(Json.parse(lines.get(i))));
                if (m != null) out.add(m);
            }
            if (lines.size() > limit * 4) rewrite(convKey, out); // keep files from growing forever
        } catch (IOException | RuntimeException e) {
            // no history yet
        }
        return out;
    }

    public void append(String convKey, Msg m) {
        try {
            Path f = historyFile(convKey);
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(m.toMap()) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // history is best-effort
        }
    }

    public void rewrite(String convKey, List<Msg> msgs) {
        try {
            Path f = historyFile(convKey);
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (Msg m : msgs) sb.append(Json.write(m.toMap())).append('\n');
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    public void deleteHistory(String convKey) {
        try {
            Files.deleteIfExists(historyFile(convKey));
        } catch (IOException ignored) {
            // best-effort
        }
    }
}

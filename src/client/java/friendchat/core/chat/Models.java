package friendchat.core.chat;

import friendchat.core.nostr.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain data shared by the engine, the store and the UI. */
public final class Models {
    private Models() { }

    public static final int SENT = 0, SENDING = 1, FAILED = 2;

    /** An encrypted blob on a Blossom server plus what the receiver needs to open it. */
    public record Media(String type, String url, String key, String sha, String cipherSha, String mime, String name,
                        int w, int h, int ms, long size) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type); m.put("url", url); m.put("key", key); m.put("sha", sha); m.put("csha", cipherSha);
            m.put("mime", mime); m.put("name", name); m.put("w", (long) w); m.put("h", (long) h);
            m.put("ms", (long) ms); m.put("size", size);
            return m;
        }

        public static Media fromMap(Map<String, Object> m) {
            if (m == null) return null;
            String type = Json.str(m, "type", "file");
            if (!type.matches("image|gif|video|voice|file")) type = "file";
            return new Media(type, Json.str(m, "url", ""), Json.str(m, "key", ""), Json.str(m, "sha", ""),
                    Json.str(m, "csha", ""), Json.str(m, "mime", ""), clean(Json.str(m, "name", "file"), 96),
                    (int) Json.num(m, "w", 0), (int) Json.num(m, "h", 0), (int) Json.num(m, "ms", 0), Json.num(m, "size", 0));
        }
    }

    public record Msg(String id, long ts, String from, String fromName, boolean mine, String text, Media media, int state) {
        public Msg withState(int s) { return new Msg(id, ts, from, fromName, mine, text, media, s); }

        public Msg withMedia(Media m, int s) { return new Msg(id, ts, from, fromName, mine, text, m, s); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("ts", ts); m.put("from", from); m.put("fn", fromName); m.put("me", mine);
            m.put("text", text); m.put("st", (long) state);
            if (media != null) m.put("media", media.toMap());
            return m;
        }

        public static Msg fromMap(Map<String, Object> m) {
            if (m == null || Json.str(m, "id", null) == null) return null;
            return new Msg(Json.str(m, "id", ""), Json.num(m, "ts", 0), Json.str(m, "from", ""), Json.str(m, "fn", ""),
                    Boolean.TRUE.equals(m.get("me")), Json.str(m, "text", ""), Media.fromMap(Json.obj(m.get("media"))),
                    (int) Json.num(m, "st", 0));
        }
    }

    public record Friend(String hex, String name) { }

    public record Group(String id, String name, String key, List<String> members) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("name", name); m.put("key", key); m.put("members", members);
            return m;
        }

        public static Group fromMap(Map<String, Object> m) {
            if (m == null) return null;
            String id = Json.str(m, "id", null), key = Json.str(m, "key", null);
            if (id == null || key == null || !id.matches("[0-9a-f]{32}")) return null;
            List<String> mem = new ArrayList<>();
            List<Object> raw = Json.arr(m.get("members"));
            if (raw != null) for (Object o : raw) if (o instanceof String s && s.matches("[0-9a-f]{64}")) mem.add(s);
            return new Group(id, clean(Json.str(m, "name", "group"), 32), key, mem);
        }
    }

    // ---- what the UI sees (immutable, rebuilt by the engine, safe to read from the render thread)
    public record FriendView(String hex, String name, String tag, int presence, int unread, String last) { }

    public record GroupView(String id, String name, int members, int unread, String last) { }

    public record ReqView(String hex, String name, String tag) { }

    public record Snapshot(List<FriendView> friends, List<GroupView> groups, List<ReqView> requests,
                           int relaysUp, int relaysTotal, String myName, String myTag, String myCode) { }

    /** Strips control characters and trims so names and file names from other people are always safe to draw. */
    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}

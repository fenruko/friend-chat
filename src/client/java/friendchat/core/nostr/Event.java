package friendchat.core.nostr;

import friendchat.core.crypto.Secp256k1;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A signed Nostr event (NIP-01). */
public record Event(String id, String pubkey, long createdAt, int kind, List<List<String>> tags, String content, String sig) {

    public static Event create(byte[] sk, String pubHex, int kind, List<List<String>> tags, String content, long createdAt) {
        String id = computeId(pubHex, createdAt, kind, tags, content);
        byte[] sig = Secp256k1.sign(HexFormat.of().parseHex(id), sk);
        return new Event(id, pubHex, createdAt, kind, tags, content, HexFormat.of().formatHex(sig));
    }

    static String computeId(String pub, long createdAt, int kind, List<List<String>> tags, String content) {
        try {
            String ser = Json.write(List.of(0, pub, createdAt, kind, tags, content));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ser.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the id matches the content and the signature is valid for the pubkey. */
    public boolean valid() {
        try {
            if (!computeId(pubkey, createdAt, kind, tags, content).equals(id)) return false;
            HexFormat h = HexFormat.of();
            return Secp256k1.verify(h.parseHex(id), h.parseHex(sig), h.parseHex(pubkey));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String tag(String name) {
        for (List<String> t : tags) if (t.size() >= 2 && t.get(0).equals(name)) return t.get(1);
        return null;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("pubkey", pubkey);
        m.put("created_at", createdAt);
        m.put("kind", (long) kind);
        m.put("tags", tags);
        m.put("content", content);
        m.put("sig", sig);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** @return the event, or null when the object is not a well-formed event. */
    public static Event fromMap(Map<String, Object> m) {
        try {
            if (m == null) return null;
            String id = Json.str(m, "id", null), pub = Json.str(m, "pubkey", null), content = Json.str(m, "content", null);
            String sig = Json.str(m, "sig", null);
            List<Object> raw = Json.arr(m.get("tags"));
            if (id == null || pub == null || content == null || sig == null || raw == null
                    || id.length() != 64 || pub.length() != 64 || sig.length() != 128) return null;
            List<List<String>> tags = new ArrayList<>();
            for (Object t : raw) {
                List<Object> row = Json.arr(t);
                if (row == null) return null;
                List<String> r = new ArrayList<>(row.size());
                for (Object x : row) r.add(x instanceof String s ? s : String.valueOf(x));
                tags.add(r);
            }
            return new Event(id, pub, Json.num(m, "created_at", 0), (int) Json.num(m, "kind", -1), tags, content, sig);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

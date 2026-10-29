package friendchat.core;

import friendchat.core.chat.*;
import friendchat.core.chat.Models.*;
import friendchat.core.media.*;
import friendchat.core.nostr.Event;
import friendchat.core.voice.VoiceFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChatTests {
    public static void main(String[] a) throws Exception {
        // voice container
        List<byte[]> pk = new ArrayList<>();
        for (int i = 0; i < 50; i++) pk.add(new byte[]{(byte) i, 2, 3});
        byte[] packed = VoiceFormat.pack(pk);
        T.eq("voice roundtrip", 50, VoiceFormat.unpack(packed).size());
        T.eq("voice duration", 1000, VoiceFormat.durationMs(packed));
        T.eq("voice garbage", null, VoiceFormat.unpack(new byte[]{1, 2, 3}));
        packed[10] = 120;
        T.eq("voice truncated", null, VoiceFormat.unpack(java.util.Arrays.copyOf(packed, 12)));

        // events: sign, verify, reject tampering
        byte[] sk = friendchat.core.crypto.Secp256k1.newSecret();
        String pub = java.util.HexFormat.of().formatHex(friendchat.core.crypto.Secp256k1.publicKey(sk));
        Event e = Event.create(sk, pub, 4242, List.of(List.of("p", pub)), "he\"llo\n", 1700000000L);
        T.ok("event valid", e.valid());
        Event round = Event.fromMap(friendchat.core.nostr.Json.obj(friendchat.core.nostr.Json.parse(e.toJson())));
        T.ok("event survives json", round != null && round.valid());
        Event bad = new Event(e.id(), e.pubkey(), e.createdAt(), e.kind(), e.tags(), "other", e.sig());
        T.ok("event tamper rejected", !bad.valid());

        // book persistence + history
        Path dir = Files.createTempDirectory("fc");
        Book b = new Book(dir);
        b.friends.put("a".repeat(64), new Friend("a".repeat(64), "Alex"));
        b.groups.put("1".repeat(32), new Group("1".repeat(32), "Crew", "key", List.of("a".repeat(64))));
        b.requests.put("b".repeat(64), "Bob");
        b.blocked.add("c".repeat(64));
        b.markDirty();
        b.saveIfDirty();
        Book b2 = new Book(dir);
        b2.load();
        T.eq("friend persisted", "Alex", b2.friends.get("a".repeat(64)).name());
        T.eq("group persisted", "Crew", b2.groups.get("1".repeat(32)).name());
        T.eq("request persisted", "Bob", b2.requests.get("b".repeat(64)));
        T.ok("blocked persisted", b2.blocked.contains("c".repeat(64)));
        b2.append("dm:x", new Msg("1", 5, "x", "X", false, "hi", null, 0));
        b2.append("dm:x", new Msg("2", 6, "x", "X", true, "yo", new Media("voice", "u", "k", "s", "c", "m", "n", 0, 0, 3000, 5), 0));
        List<Msg> h = b2.loadHistory("dm:x", 10);
        T.eq("history size", 2, h.size());
        T.eq("history media", 3000, h.get(1).media().ms());

        // media pipeline reused from chatimage
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(3000, 2000, 1);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        Limits lim = new Limits();
        PreparePipeline.Outcome o = PreparePipeline.prepare("big.png", png.toByteArray(), lim);
        T.ok("big image accepted and scaled", o instanceof PreparePipeline.Outcome.Ok ok && ok.attachment().width() <= lim.maxImageDimension);
        T.ok("junk rejected", PreparePipeline.prepare("x.png", new byte[200], lim) instanceof PreparePipeline.Outcome.Rejected);
        T.eq("name sanitised", "evil.png", PreparedAttachment.sanitizeName("../../evil.png"));
        T.eq("clean strips control", "ab", Models.clean("a\u0000‮b", 10));
        FcConfig c = new FcConfig();
        T.eq("default relays", 3, c.relayList().size());
        T.done("ChatTests");
    }
}

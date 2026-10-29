package friendchat.core.chat;

import friendchat.core.chat.Models.*;
import friendchat.core.crypto.Bech32;
import friendchat.core.crypto.Nip44;
import friendchat.core.crypto.Secp256k1;
import friendchat.core.media.ImageCodec;
import friendchat.core.media.Limits;
import friendchat.core.media.PreparePipeline;
import friendchat.core.media.PreparedAttachment;
import friendchat.core.nostr.Blossom;
import friendchat.core.nostr.Event;
import friendchat.core.nostr.Json;
import friendchat.core.nostr.RelayPool;
import friendchat.core.ui.Toasts;
import friendchat.core.voice.VoiceFormat;
import friendchat.core.voice.VoiceIo;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Everything that is not drawing. One low-priority daemon thread owns all state; the render thread only reads the
 * immutable {@link Snapshot} and message lists (copy-on-write), so the game never waits on this class.
 * Transport is Nostr: identity = keypair, DMs = NIP-44 encrypted events, groups = shared-key encrypted events,
 * presence = short-lived ephemeral events readable only by your friends, media = encrypted blobs on Blossom servers.
 */
public final class ChatEngine implements ChatBackend {
    public static final int KIND_MSG = 4242, KIND_GROUP = 4243, KIND_PRESENCE = 20420;
    public static final int OFFLINE = 0, ONLINE = 1, IN_GAME = 2;
    private static final long PRESENCE_TTL_MS = 150_000;
    private static final int KEEP = 500, MAX_DOWNLOAD = 40 * 1024 * 1024;
    /** Encrypted blobs up to this size travel inside the (already encrypted) message itself: no storage server needed. */
    static final int INLINE_MAX = 24 * 1024;

    private final Path dir;
    private final FcConfig cfg;
    private final Toasts toasts;
    private final Supplier<VoiceIo> voiceFactory;
    private final Book book;
    private final Limits limits = new Limits();

    private final ScheduledThreadPoolExecutor ses;
    private final ExecutorService io;
    private final HttpClient http;
    private RelayPool pool;

    private byte[] sk;
    private String me = "";
    private final Map<String, byte[]> convKeys = new HashMap<>();
    private final Map<String, Long> presenceAt = new HashMap<>();
    private final Map<String, Integer> presenceState = new HashMap<>();
    private final Map<String, Long> repliedAt = new HashMap<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 4000; }
    };

    private static final class Conv {
        volatile List<Msg> list = List.of();
        volatile boolean loaded;
        final Set<String> ids = new HashSet<>();
        int unread;
        long lastTs;
        String last = "";
    }

    private final Map<String, Conv> convs = new HashMap<>();
    private final Object convLock = new Object();

    private volatile Snapshot snap = new Snapshot(List.of(), List.of(), List.of(), 0, 0, "", "", "");
    private final AtomicInteger version = new AtomicInteger();
    private volatile int totalUnread;
    private volatile String viewing;
    private volatile int localState = ONLINE;
    private volatile int publishedState = -1;

    // media
    private final Map<String, BufferedImage> images = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 24; }
    });
    private final Set<String> loading = ConcurrentHashSetHolder.create();
    private final Set<String> failed = ConcurrentHashSetHolder.create();
    private volatile VoiceIo voice;
    private volatile VoiceIo.Recording recording;
    private volatile VoiceIo.Playback playback;
    private volatile String playingId = "";

    public ChatEngine(Path dir, FcConfig cfg, Toasts toasts, Supplier<VoiceIo> voiceFactory) {
        this.dir = dir;
        this.cfg = cfg;
        this.toasts = toasts;
        this.voiceFactory = voiceFactory;
        this.book = new Book(dir);
        limits.maxFileBytes = cfg.maxFileMb * 1024 * 1024;
        ses = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "friendchat-engine"));
        ses.setRemoveOnCancelPolicy(true);
        io = Executors.newFixedThreadPool(2, r -> daemon(r, "friendchat-io"));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    }

    // =================================================================== lifecycle

    public void start(String defaultName) {
        ses.execute(() -> {
            try {
                init(defaultName);
            } catch (RuntimeException | IOException e) {
                System.err.println("[friend-chat] failed to start: " + e);
            }
        });
    }

    private void init(String defaultName) throws IOException {
        Files.createDirectories(dir);
        Path keyFile = dir.resolve("identity.key");
        if (Files.isRegularFile(keyFile)) sk = HexFormat.of().parseHex(Files.readString(keyFile).trim());
        if (!Secp256k1.validSecret(sk)) {
            sk = Secp256k1.newSecret();
            Files.writeString(keyFile, HexFormat.of().formatHex(sk));
            try {
                Files.setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // not a POSIX filesystem (Windows): the user profile folder is already private
            }
        }
        me = HexFormat.of().formatHex(Secp256k1.publicKey(sk));
        if (cfg.name.isBlank()) {
            cfg.name = Models.clean(defaultName == null || defaultName.isBlank() ? "Player" : defaultName, 24);
            cfg.save(dir.resolve("friend-chat.properties"));
        }
        book.load();
        toasts.setEnabled(cfg.toasts);
        pool = new RelayPool(http, ses, cfg.relayList(), new RelayPool.Listener() {
            @Override public void onMessage(String relay, String raw) { ses.execute(() -> handleRaw(raw)); }

            @Override public void onConnected(String relay, int n) {
                ses.execute(() -> {
                    publishedState = -1;
                    publishPresenceAll(true);
                    refresh();
                });
            }
        });
        resubscribe();
        pool.start();
        ses.scheduleWithFixedDelay(() -> safe(this::tick), 30, 30, TimeUnit.SECONDS);
        ses.scheduleWithFixedDelay(() -> safe(() -> publishPresenceAll(false)), 60, 60, TimeUnit.SECONDS);
        refresh();
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[friend-chat] " + e);
        }
    }

    private void tick() {
        book.saveIfDirty();
        refresh();
    }

    /** Best effort: tell friends we left, flush the book, close sockets. Called on game shutdown. */
    public void stop() {
        try {
            ses.submit(() -> {
                localState = OFFLINE;
                publishPresenceAll(false);
                book.lastSeen = System.currentTimeMillis() / 1000;
                book.markDirty();
                book.saveIfDirty();
            }).get(1500, TimeUnit.MILLISECONDS);
            Thread.sleep(250); // let the frames leave the socket
        } catch (Exception ignored) {
            // shutting down anyway
        }
        if (pool != null) pool.stop();
        stopVoice();
        ses.shutdownNow();
        io.shutdownNow();
    }

    private void resubscribe() {
        long now = System.currentTimeMillis() / 1000;
        long since = book.lastSeen > 0 ? Math.max(0, book.lastSeen - 120) : now - 7 * 86400;
        StringBuilder f = new StringBuilder();
        f.append("{\"kinds\":[").append(KIND_MSG).append("],\"#p\":[\"").append(me).append("\"],\"since\":").append(since).append('}');
        f.append(",{\"kinds\":[").append(KIND_PRESENCE).append("],\"#p\":[\"").append(me).append("\"],\"since\":").append(now - 30).append('}');
        if (!book.groups.isEmpty()) {
            f.append(",{\"kinds\":[").append(KIND_GROUP).append("],\"#g\":[");
            boolean first = true;
            for (String id : book.groups.keySet()) {
                if (!first) f.append(',');
                first = false;
                f.append('"').append(id).append('"');
            }
            f.append("],\"since\":").append(since).append('}');
        }
        pool.subscribe(f.toString());
    }

    // =================================================================== UI-facing reads

    @Override public Snapshot snapshot() { return snap; }

    @Override public int version() { return version.get(); }

    public int totalUnread() { return totalUnread; }

    @Override public int recordingMs() {
        VoiceIo.Recording r = recording;
        return r == null ? -1 : r.elapsedMs();
    }

    @Override public String playingId() { return playingId; }

    public void setLocalState(int state) {
        if (state == localState) return;
        localState = state;
        ses.execute(() -> publishPresenceAll(false));
    }

    @Override public void setViewing(String convKey) {
        viewing = convKey;
        if (convKey != null) ses.execute(() -> {
            Conv c = conv(convKey);
            if (c.unread > 0) {
                c.unread = 0;
                refresh();
            }
        });
    }

    /** The messages of a conversation (oldest first). The first call kicks off loading the history file. */
    @Override public List<Msg> messages(String convKey) {
        Conv c;
        synchronized (convLock) {
            c = convs.computeIfAbsent(convKey, k -> new Conv());
        }
        if (!c.loaded) ses.execute(() -> conv(convKey));
        return c.list;
    }

    @Override public String title(String convKey) {
        Snapshot s = snap;
        if (convKey.startsWith("dm:")) for (FriendView f : s.friends()) if (convKey.equals("dm:" + f.hex())) return f.name();
        if (convKey.startsWith("g:")) for (GroupView g : s.groups()) if (convKey.equals("g:" + g.id())) return g.name();
        return "CHAT";
    }

    // =================================================================== actions (any thread)

    /** @return an error for the user, or null when the friend was added. */
    @Override public String addFriend(String code) {
        String t = code == null ? "" : code.trim();
        byte[] raw = null;
        if (t.regionMatches(true, 0, "npub1", 0, 5)) raw = Bech32.decode("npub", t);
        else if (t.matches("(?i)[0-9a-f]{64}")) raw = HexFormat.of().parseHex(t);
        if (raw == null) return "That is not a friend code (it starts with npub1).";
        String hex = HexFormat.of().formatHex(raw);
        if (hex.equals(me)) return "That is your own code.";
        ses.execute(() -> {
            if (!book.friends.containsKey(hex)) {
                String known = book.requests.remove(hex);
                book.blocked.remove(hex);
                book.friends.put(hex, new Friend(hex, known != null ? known : "npub " + hex.substring(0, 4)));
                book.markDirty();
                sendDirect(hex, helloPayload());
                publishPresenceTo(hex, true);
                refresh();
            }
        });
        return null;
    }

    @Override public void acceptRequest(String hex) {
        ses.execute(() -> {
            String name = book.requests.remove(hex);
            if (name == null) return;
            book.friends.put(hex, new Friend(hex, name));
            book.markDirty();
            sendDirect(hex, helloPayload());
            publishPresenceTo(hex, true);
            refresh();
        });
    }

    @Override public void blockRequest(String hex) {
        ses.execute(() -> {
            book.requests.remove(hex);
            book.friends.remove(hex);
            book.blocked.add(hex);
            book.markDirty();
            refresh();
        });
    }

    @Override public void removeFriend(String hex) {
        ses.execute(() -> {
            book.friends.remove(hex);
            presenceState.remove(hex);
            book.markDirty();
            refresh();
        });
    }

    @Override public void createGroup(String name, List<String> memberHex) {
        ses.execute(() -> {
            byte[] id = new byte[16];
            new java.security.SecureRandom().nextBytes(id);
            List<String> members = new ArrayList<>();
            members.add(me);
            for (String m : memberHex) if (book.friends.containsKey(m) && !members.contains(m)) members.add(m);
            Group g = new Group(HexFormat.of().formatHex(id), Models.clean(name.isBlank() ? "Group" : name, 32),
                    Base64.getEncoder().encodeToString(Nip44.newKey()), members);
            book.groups.put(g.id(), g);
            book.markDirty();
            resubscribe();
            for (String m : members) if (!m.equals(me)) sendDirect(m, invitePayload(g));
            refresh();
        });
    }

    @Override public void leaveGroup(String id) {
        ses.execute(() -> {
            if (book.groups.remove(id) != null) {
                book.markDirty();
                book.deleteHistory("g:" + id);
                synchronized (convLock) {
                    convs.remove("g:" + id);
                }
                resubscribe();
                refresh();
            }
        });
    }

    @Override public void sendText(String convKey, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return;
        String body = t.length() > 4000 ? t.substring(0, 4000) : t;
        ses.execute(() -> {
            String id = UUID.randomUUID().toString();
            Map<String, Object> p = payload("m", id);
            p.put("m", body);
            if (post(convKey, p)) addMessage(convKey, new Msg(id, now(), me, cfg.name, true, body, null, Models.SENT), false);
        });
    }

    /** Reads, sanitises (reusing chatimage's pipeline), encrypts, uploads and sends a picked file. */
    @Override public void sendFile(String convKey, File file) {
        io.execute(() -> {
            try {
                if (file.length() > 64L * 1024 * 1024) {
                    toasts.push("File too big", file.getName(), Toasts.ERROR);
                    return;
                }
                byte[] bytes = Files.readAllBytes(file.toPath());
                PreparePipeline.Outcome out = PreparePipeline.prepare(file.getName(), bytes, limits);
                if (out instanceof PreparePipeline.Outcome.Rejected r) {
                    toasts.push("Can't send that", r.reason(), Toasts.ERROR);
                    return;
                }
                PreparedAttachment a = ((PreparePipeline.Outcome.Ok) out).attachment();
                String type = switch (a.kind()) {
                    case IMAGE -> "image";
                    case GIF -> "gif";
                    case VIDEO -> "video";
                };
                upload(convKey, type, a.fileName(), a.mime(), a.bytes(), a.width(), a.height(), a.durationMs());
            } catch (IOException e) {
                toasts.push("Can't read file", file.getName(), Toasts.ERROR);
            }
        });
    }

    // ---- voice notes
    @Override public void startRecording() {
        if (recording != null) return;
        io.execute(() -> {
            try {
                if (voice == null) voice = voiceFactory.get();
                if (voice == null) throw new IllegalStateException("voice is not available");
                recording = voice.startRecording();
                bump();
            } catch (Exception | LinkageError e) {
                recording = null;
                toasts.push("No microphone", String.valueOf(e.getMessage()), Toasts.ERROR);
            }
        });
    }

    @Override public void stopRecordingAndSend(String convKey) {
        VoiceIo.Recording r = recording;
        recording = null;
        bump();
        if (r == null) return;
        io.execute(() -> {
            byte[] data = r.finish();
            if (data == null || VoiceFormat.durationMs(data) < 600) {
                toasts.push("Voice note too short", "Hold on a bit longer", Toasts.INFO);
                return;
            }
            upload(convKey, "voice", "voice-note.fcv", VoiceFormat.MIME, data, 0, 0, VoiceFormat.durationMs(data));
        });
    }

    @Override public void cancelRecording() {
        VoiceIo.Recording r = recording;
        recording = null;
        if (r != null) io.execute(r::cancel);
        bump();
    }

    @Override public void playVoice(Msg m) {
        if (m.media() == null) return;
        if (m.id().equals(playingId)) {
            stopVoice();
            return;
        }
        stopVoice();
        playingId = m.id();
        bump();
        io.execute(() -> {
            try {
                byte[] data = fetch(m.media());
                if (voice == null) voice = voiceFactory.get();
                if (voice == null) throw new IllegalStateException("voice is not available");
                if (!m.id().equals(playingId)) return; // user pressed something else meanwhile
                VoiceIo.Playback pb = voice.play(data);
                playback = pb;
                ses.schedule(() -> pollPlayback(pb), 400, TimeUnit.MILLISECONDS);
            } catch (Exception | LinkageError e) {
                playingId = "";
                bump();
                toasts.push("Can't play voice note", String.valueOf(e.getMessage()), Toasts.ERROR);
            }
        });
    }

    private void pollPlayback(VoiceIo.Playback pb) {
        if (playback != pb) return;
        if (pb.done()) {
            playback = null;
            playingId = "";
            bump();
        } else {
            ses.schedule(() -> pollPlayback(pb), 400, TimeUnit.MILLISECONDS);
        }
    }

    public void stopVoice() {
        VoiceIo.Playback pb = playback;
        playback = null;
        playingId = "";
        if (pb != null) pb.stop();
        VoiceIo.Recording r = recording;
        recording = null;
        if (r != null) r.cancel();
        bump();
    }

    // ---- media display
    /** 0 = not requested, 1 = loading, 2 = ready, 3 = failed. Asking for an image starts loading it. */
    @Override public int mediaStatus(Msg m) {
        if (m.media() == null) return 0;
        if (images.containsKey(m.id())) return 2;
        if (failed.contains(m.id())) return 3;
        return loading.contains(m.id()) ? 1 : 0;
    }

    @Override public BufferedImage image(Msg m) {
        Media md = m.media();
        if (md == null || !(md.type().equals("image") || md.type().equals("gif"))) return null;
        BufferedImage img = images.get(m.id());
        if (img != null) return img;
        if (m.state() == Models.SENDING || md.url().isEmpty() || failed.contains(m.id())) return null;
        if (loading.add(m.id())) io.execute(() -> {
            try {
                BufferedImage decoded = ImageCodec.decode(fetch(md));
                if (decoded == null) throw new IOException("unsupported image");
                images.put(m.id(), ImageCodec.fit(decoded, 640));
            } catch (IOException | RuntimeException e) {
                failed.add(m.id());
            } finally {
                loading.remove(m.id());
                bump();
            }
        });
        return null;
    }

    /** Downloads, decrypts and saves a received file into friend-chat/downloads. */
    @Override public void save(Msg m) {
        if (m.media() == null) return;
        io.execute(() -> {
            try {
                byte[] data = fetch(m.media());
                Path out = dir.resolve("downloads");
                Files.createDirectories(out);
                String name = PreparedAttachment.sanitizeName(m.media().name());
                Path f = out.resolve(name);
                for (int i = 1; Files.exists(f); i++) f = out.resolve(i + "-" + name);
                Files.write(f, data);
                toasts.push("Saved", f.getFileName().toString(), Toasts.INFO);
            } catch (IOException | RuntimeException e) {
                toasts.push("Download failed", String.valueOf(e.getMessage()), Toasts.ERROR);
            }
        });
    }

    // =================================================================== internals: sending

    private long now() { return System.currentTimeMillis() / 1000; }

    private Map<String, Object> payload(String type, String id) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("t", type);
        p.put("id", id);
        p.put("n", cfg.name);
        return p;
    }

    private Map<String, Object> helloPayload() { return payload("hello", UUID.randomUUID().toString()); }

    private Map<String, Object> invitePayload(Group g) {
        Map<String, Object> p = payload("ginv", UUID.randomUUID().toString());
        p.put("gid", g.id());
        p.put("gn", g.name());
        p.put("gk", g.key());
        p.put("mem", g.members());
        return p;
    }

    private byte[] convKey(String hex) {
        return convKeys.computeIfAbsent(hex, h -> Nip44.conversationKey(sk, HexFormat.of().parseHex(h)));
    }

    private void sendDirect(String hex, Map<String, Object> payload) {
        post("dm:" + hex, payload);
    }

    /** Encrypts and publishes. Runs on the engine thread. */
    private boolean post(String convKey, Map<String, Object> payload) {
        try {
            String json = Json.write(payload);
            Event ev;
            if (convKey.startsWith("dm:")) {
                String hex = convKey.substring(3);
                ev = Event.create(sk, me, KIND_MSG, List.of(List.of("p", hex)), Nip44.encrypt(json, convKey(hex)), now());
            } else {
                Group g = book.groups.get(convKey.substring(2));
                if (g == null) return false;
                byte[] sealed = Nip44.seal(Base64.getDecoder().decode(g.key()), json.getBytes(StandardCharsets.UTF_8));
                ev = Event.create(sk, me, KIND_GROUP, List.of(List.of("g", g.id())), Base64.getEncoder().encodeToString(sealed), now());
            }
            pool.publish(ev);
            return true;
        } catch (RuntimeException e) {
            toasts.push("Message not sent", String.valueOf(e.getMessage()), Toasts.ERROR);
            return false;
        }
    }

    private void upload(String convKey, String type, String name, String mime, byte[] plain, int w, int h, int ms) {
        String id = UUID.randomUUID().toString();
        Media pending = new Media(type, "", "", PreparedAttachment.sha256Hex(plain), "", mime, name, w, h, ms, plain.length);
        Msg local = new Msg(id, now(), me, cfg.name, true, "", pending, Models.SENDING);
        ses.execute(() -> addMessage(convKey, local, false));
        if (type.equals("image") || type.equals("gif")) {
            BufferedImage own = ImageCodec.decode(plain);
            if (own != null) images.put(id, ImageCodec.fit(own, 640));
        }
        try {
            byte[] key = Nip44.newKey();
            byte[] blob = Nip44.seal(key, plain);
            String csha = PreparedAttachment.sha256Hex(blob);
            String keyB64 = Base64.getEncoder().encodeToString(key);
            String wireUrl, localUrl;
            if (blob.length <= INLINE_MAX) {
                storeBlob(csha, blob);
                wireUrl = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(blob);
                localUrl = LOCAL + csha;
            } else {
                wireUrl = localUrl = Blossom.upload(http, cfg.blossomList(), new Blossom.Signer(sk, me), blob, csha);
            }
            Media wireMedia = new Media(type, wireUrl, keyB64, pending.sha(), csha, mime, name, w, h, ms, plain.length);
            Media localMedia = new Media(type, localUrl, keyB64, pending.sha(), csha, mime, name, w, h, ms, plain.length);
            ses.execute(() -> {
                Map<String, Object> p = payload("media", id);
                p.put("media", wireMedia.toMap());
                boolean ok = post(convKey, p);
                replaceMessage(convKey, local.withMedia(localMedia, ok ? Models.SENT : Models.FAILED), true);
            });
        } catch (IOException | RuntimeException e) {
            System.err.println("[friend-chat] upload failed: " + e);
            ses.execute(() -> replaceMessage(convKey, local.withState(Models.FAILED), true));
            toasts.push("Upload failed", String.valueOf(e.getMessage()), Toasts.ERROR);
        }
    }

    private static final String LOCAL = "local:";

    private Path blobFile(String csha) {
        return dir.resolve("blobs").resolve(csha);
    }

    private void storeBlob(String csha, byte[] blob) throws IOException {
        Files.createDirectories(dir.resolve("blobs"));
        Files.write(blobFile(csha), blob);
    }

    private byte[] fetch(Media m) throws IOException {
        if (m.key().isEmpty() || !m.cipherSha().matches("[0-9a-f]{64}")) throw new IOException("no media to fetch");
        byte[] blob;
        if (m.url().startsWith(LOCAL) && Files.isRegularFile(blobFile(m.cipherSha()))) {
            blob = Files.readAllBytes(blobFile(m.cipherSha()));
            if (!PreparedAttachment.sha256Hex(blob).equals(m.cipherSha())) throw new IOException("stored file is damaged");
        } else {
            blob = Blossom.download(http, m.url().startsWith(LOCAL) ? "" : m.url(), cfg.blossomList(), m.cipherSha(), MAX_DOWNLOAD);
        }
        byte[] plain = Nip44.open(Base64.getDecoder().decode(m.key()), blob);
        if (plain == null || !PreparedAttachment.sha256Hex(plain).equalsIgnoreCase(m.sha())) throw new IOException("file was corrupted or tampered with");
        return plain;
    }

    // =================================================================== internals: receiving

    private void handleRaw(String raw) {
        Object o = Json.parse(raw);
        List<Object> a = Json.arr(o);
        if (a == null || a.size() < 3 || !"EVENT".equals(a.get(0))) return;
        Event e = Event.fromMap(Json.obj(a.get(2)));
        if (e == null || e.pubkey().equals(me) || seen.put(e.id(), Boolean.TRUE) != null) return;
        if (book.blocked.contains(e.pubkey())) return;
        switch (e.kind()) {
            case KIND_MSG -> {
                if (!me.equals(e.tag("p")) || !e.valid()) return;
                String json = Nip44.decrypt(e.content(), convKey(e.pubkey()));
                if (json != null) dispatch(e, "dm:" + e.pubkey(), Json.obj(Json.parse(json)), false);
            }
            case KIND_PRESENCE -> {
                if (!me.equals(e.tag("p")) || !book.friends.containsKey(e.pubkey())) return;
                if (Math.abs(now() - e.createdAt()) > 120 || !e.valid()) return;
                String json = Nip44.decrypt(e.content(), convKey(e.pubkey()));
                if (json != null) onPresence(e.pubkey(), Json.obj(Json.parse(json)));
            }
            case KIND_GROUP -> {
                Group g = book.groups.get(e.tag("g"));
                if (g == null || !e.valid()) return;
                byte[] plain;
                try {
                    plain = Nip44.open(Base64.getDecoder().decode(g.key()), Base64.getDecoder().decode(e.content()));
                } catch (IllegalArgumentException ex) {
                    return;
                }
                if (plain != null) dispatch(e, "g:" + g.id(), Json.obj(Json.parse(new String(plain, StandardCharsets.UTF_8))), true);
            }
            default -> { }
        }
    }

    private void dispatch(Event e, String convKey, Map<String, Object> p, boolean group) {
        if (p == null) return;
        String from = e.pubkey(), type = Json.str(p, "t", ""), id = Json.str(p, "id", e.id());
        String name = Models.clean(Json.str(p, "n", ""), 24);
        boolean friend = book.friends.containsKey(from);
        if (!group && !friend) {
            if (!book.requests.containsKey(from)) toasts.push(name.isEmpty() ? "New request" : name, "wants to be your friend", Toasts.MESSAGE);
            if (!name.isEmpty() || !book.requests.containsKey(from)) {
                book.requests.put(from, name.isEmpty() ? "npub " + from.substring(0, 4) : name);
                book.markDirty();
            }
        } else if (friend && !name.isEmpty() && !name.equals(book.friends.get(from).name())) {
            book.friends.put(from, new Friend(from, name));
            book.markDirty();
        }
        book.lastSeen = Math.max(book.lastSeen, e.createdAt());
        String display = group ? (name.isEmpty() ? "npub " + from.substring(0, 4) : name) : displayName(from, name);
        switch (type) {
            case "m" -> {
                String text = Models.clean(Json.str(p, "m", ""), 4000);
                if (!text.isEmpty()) addMessage(convKey, new Msg(id, e.createdAt(), from, display, false, text, null, Models.SENT), true);
            }
            case "media" -> {
                Media m = Media.fromMap(Json.obj(p.get("media")));
                if (m != null && m.url().startsWith("data:") && m.cipherSha().matches("[0-9a-f]{64}")) {
                    try {
                        byte[] blob = Base64.getDecoder().decode(m.url().substring(m.url().indexOf(',') + 1));
                        if (blob.length > INLINE_MAX + 64 || !PreparedAttachment.sha256Hex(blob).equals(m.cipherSha())) return;
                        storeBlob(m.cipherSha(), blob);
                        m = new Media(m.type(), LOCAL + m.cipherSha(), m.key(), m.sha(), m.cipherSha(), m.mime(), m.name(), m.w(), m.h(), m.ms(), m.size());
                    } catch (IOException | IllegalArgumentException ex) {
                        return;
                    }
                }
                if (m != null && m.cipherSha().length() == 64) addMessage(convKey, new Msg(id, e.createdAt(), from, display, false, "", m, Models.SENT), true);
            }
            case "ginv" -> {
                if (!friend) return;
                Group g = Group.fromMap(Map.of("id", Json.str(p, "gid", ""), "name", Json.str(p, "gn", "Group"),
                        "key", Json.str(p, "gk", ""), "members", Json.arr(p.get("mem")) == null ? List.of() : Json.arr(p.get("mem"))));
                if (g != null && !book.groups.containsKey(g.id())) {
                    book.groups.put(g.id(), g);
                    book.markDirty();
                    resubscribe();
                    toasts.push(g.name(), display + " added you to a group", Toasts.MESSAGE);
                }
            }
            default -> { } // "hello" only creates the request above
        }
        refresh();
    }

    private String displayName(String hex, String fromPayload) {
        Friend f = book.friends.get(hex);
        if (f != null) return f.name();
        String r = book.requests.get(hex);
        return r != null ? r : fromPayload;
    }

    private void onPresence(String from, Map<String, Object> p) {
        if (p == null) return;
        int s = (int) Math.max(0, Math.min(2, Json.num(p, "s", 0)));
        presenceState.put(from, s);
        presenceAt.put(from, System.currentTimeMillis());
        if (Json.num(p, "r", 0) == 1) { // they just came online and want to know about us
            long last = repliedAt.getOrDefault(from, 0L), nowMs = System.currentTimeMillis();
            if (nowMs - last > 20_000) {
                repliedAt.put(from, nowMs);
                publishPresenceTo(from, false);
            }
        }
        refresh();
    }

    // =================================================================== internals: presence

    private void publishPresenceAll(boolean request) {
        if (pool == null) return;
        publishedState = localState;
        int n = 0;
        for (String hex : book.friends.keySet()) {
            if (n++ >= 60) break;
            publishPresenceTo(hex, request);
        }
    }

    private void publishPresenceTo(String hex, boolean request) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", "pres");
            p.put("s", (long) localState);
            p.put("n", cfg.name);
            p.put("r", request ? 1L : 0L);
            pool.publish(Event.create(sk, me, KIND_PRESENCE, List.of(List.of("p", hex)), Nip44.encrypt(Json.write(p), convKey(hex)), now()));
        } catch (RuntimeException ignored) {
            // a bad key for one friend must not stop the others
        }
    }

    // =================================================================== internals: conversations

    private Conv conv(String key) {
        Conv c;
        synchronized (convLock) {
            c = convs.computeIfAbsent(key, k -> new Conv());
        }
        if (!c.loaded) {
            List<Msg> hist = book.loadHistory(key, KEEP);
            List<Msg> merged = new ArrayList<>(hist);
            for (Msg m : c.list) if (!c.ids.contains(m.id())) merged.add(m);
            for (Msg m : hist) c.ids.add(m.id());
            c.list = List.copyOf(merged);
            if (!merged.isEmpty()) {
                Msg l = merged.get(merged.size() - 1);
                c.lastTs = l.ts();
                c.last = preview(l);
            }
            c.loaded = true;
            bump();
        }
        return c;
    }

    private static String preview(Msg m) {
        if (m.media() == null) return m.text();
        return switch (m.media().type()) {
            case "voice" -> "voice note";
            case "image" -> "photo";
            case "gif" -> "gif";
            case "video" -> "video";
            default -> "file";
        };
    }

    private void addMessage(String key, Msg m, boolean incoming) {
        Conv c = conv(key);
        if (!c.ids.add(m.id())) {
            if (!incoming) replaceMessage(key, m, false);
            return;
        }
        List<Msg> next = new ArrayList<>(c.list);
        int at = next.size();
        while (at > 0 && next.get(at - 1).ts() > m.ts() && incoming) at--; // keep relay-delayed messages in time order
        next.add(at, m);
        if (next.size() > KEEP) next = new ArrayList<>(next.subList(next.size() - KEEP, next.size()));
        c.list = List.copyOf(next);
        c.lastTs = Math.max(c.lastTs, m.ts());
        c.last = preview(m);
        if (m.state() != Models.SENDING) book.append(key, m);
        if (incoming && !key.equals(viewing)) {
            c.unread++;
            String title = key.startsWith("g:") ? title(key) + " · " + m.fromName() : m.fromName();
            toasts.push(title, c.last, Toasts.MESSAGE);
        }
        refresh();
    }

    private void replaceMessage(String key, Msg m, boolean persist) {
        Conv c = conv(key);
        List<Msg> next = new ArrayList<>(c.list);
        for (int i = 0; i < next.size(); i++) if (next.get(i).id().equals(m.id())) next.set(i, m);
        c.list = List.copyOf(next);
        if (persist) book.append(key, m);
        refresh();
    }

    // =================================================================== snapshot

    private void bump() { version.incrementAndGet(); }

    private void refresh() {
        long nowMs = System.currentTimeMillis();
        List<FriendView> fv = new ArrayList<>();
        int unread = 0;
        for (Friend f : book.friends.values()) {
            Conv c = peek("dm:" + f.hex());
            int pres = nowMs - presenceAt.getOrDefault(f.hex(), 0L) > PRESENCE_TTL_MS ? OFFLINE : presenceState.getOrDefault(f.hex(), OFFLINE);
            fv.add(new FriendView(f.hex(), f.name(), f.hex().substring(0, 4), pres, c == null ? 0 : c.unread, c == null ? "" : c.last));
            unread += c == null ? 0 : c.unread;
        }
        fv.sort(Comparator.comparingInt((FriendView f) -> -f.presence()).thenComparing(f -> f.name().toLowerCase()));
        List<GroupView> gv = new ArrayList<>();
        for (Group g : book.groups.values()) {
            Conv c = peek("g:" + g.id());
            gv.add(new GroupView(g.id(), g.name(), g.members().size(), c == null ? 0 : c.unread, c == null ? "" : c.last));
            unread += c == null ? 0 : c.unread;
        }
        List<ReqView> rv = new ArrayList<>();
        for (Map.Entry<String, String> r : book.requests.entrySet()) {
            rv.add(new ReqView(r.getKey(), r.getValue(), r.getKey().substring(0, 4)));
            Conv c = peek("dm:" + r.getKey());
            unread += c == null ? 0 : c.unread;
        }
        String code = me.isEmpty() ? "" : Bech32.encode("npub", HexFormat.of().parseHex(me));
        Snapshot s = new Snapshot(List.copyOf(fv), List.copyOf(gv), List.copyOf(rv), pool == null ? 0 : pool.connected(),
                pool == null ? 0 : pool.total(), cfg.name, me.isEmpty() ? "" : me.substring(0, 4), code);
        totalUnread = unread + rv.size();
        if (!s.equals(snap)) {
            snap = s;
            bump();
        }
    }

    private Conv peek(String key) {
        synchronized (convLock) {
            return convs.get(key);
        }
    }

    private static final class ConcurrentHashSetHolder {
        static Set<String> create() { return java.util.concurrent.ConcurrentHashMap.newKeySet(); }
    }
}

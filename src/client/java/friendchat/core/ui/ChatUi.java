package friendchat.core.ui;

import friendchat.core.chat.ChatBackend;
import friendchat.core.chat.Models.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The whole chat window, Minecraft-free: layout, hover/press art and what each button does. The game-side screen only
 * (1) turns every {@link Region} into a real Button widget so the game's own click handling fires {@link #press}, and
 * (2) hosts the one text box at {@link #inputRect}. No raw mouse coordinates are used for clicks anywhere.
 */
public final class ChatUi {
    public interface Host {
        void close();

        void copy(String text);

        void pickFile(Consumer<File> then);
    }

    public static final int TAB_FRIENDS = 1, TAB_GROUPS = 2, TAB_REQ = 3, ROW = 4, ADD_FRIEND = 5, NEW_GROUP = 6,
            COPY_CODE = 7, CLOSE = 8, SEND = 9, ATTACH = 10, MIC = 11, MSG_ACTION = 12, ACCEPT = 13, BLOCK = 14,
            REMOVE = 15, LEAVE = 16, TOGGLE = 17, CREATE = 18, CANCEL = 19;
    public static final int NORMAL = 0, ADDING = 1, GROUPING = 2;
    private static final int PLAIN = 0, PRIMARY = 1, SELECTED = 2, INVISIBLE = 3, LIST = 4, DANGER = 5;
    private static final int LINE = 10, ROW_H = 20;

    /** One clickable thing. Also carries what to draw so layout and painting can never disagree. */
    public static final class Region {
        public int x0, y0, x1, y1, id, arg;
        String label = "", title = "", sub = "";
        Runs icon;
        int style, dot = -1, badge;
        boolean on;

        public int w() { return x1 - x0; }

        public int h() { return y1 - y0; }
    }

    // ---- icons (same tiny bitmap idea as PixelTunes)
    private static final Runs I_CLIP = Runs.ofRows("..###", ".#...", "#.##.", "#.#.#", "#.##.", ".#...", "..###");
    private static final Runs I_MIC = Runs.ofRows(".###.", "#####", "#####", "#####", ".###.", "..#..", ".###.");
    private static final Runs I_SEND = Runs.ofRows("#....", "###..", "#####", "###..", "#....");
    private static final Runs I_PLAY = Runs.ofRows("#...", "##..", "###.", "####", "###.", "##..", "#...");
    private static final Runs I_STOP = Runs.ofRows("#####", "#####", "#####", "#####", "#####");
    private static final Runs I_SAVE = Runs.ofRows("..#..", "..#..", "#####", ".###.", "..#..", "#####");
    private static final Runs I_X = Runs.ofRows("#...#", ".#.#.", "..#..", ".#.#.", "#...#");
    private static final Runs I_CHECK = Runs.ofRows("....#", "...#.", "#.#..", ".#...");
    private static final Runs I_BUBBLE = Runs.ofRows(".#####.", "#.....#", "#.....#", "#.....#", ".#####.", "..#....", ".#.....");

    private final ChatBackend be;
    private final Host host;
    private Theme t = Theme.preset("PURPLE");
    private int p = 1;
    private String themeName = "PURPLE";

    private int tab = TAB_FRIENDS, mode = NORMAL, sideScroll, msgScroll;
    private String conv;
    private String status = "";
    private long statusUntil;
    private final Set<String> picked = new HashSet<>();
    private final Map<String, Runs> labelCache = new HashMap<>();
    private final Map<String, String[]> wrapCache = new HashMap<>();
    private int wrapWidth = -1;

    private final List<Region> regions = new ArrayList<>();
    private int sx0, sy0, sx1, sy1; // sidebar list area
    private int mx0, my0, mx1, my1; // message area
    private int ix0, iy0, ix1, iy1; // input box
    private int panelX0, panelY0, panelX1, panelY1;
    private int lastMsgTotal;

    public ChatUi(ChatBackend be, Host host) {
        this.be = be;
        this.host = host;
    }

    public void setTheme(String name, int pixelSize) {
        if (!name.equals(themeName)) {
            themeName = name;
            t = Theme.preset(name);
        }
        p = Math.max(1, Math.min(2, pixelSize));
    }

    public int mode() { return mode; }

    public String conversation() { return conv; }

    public void openConversation(String key) {
        conv = key;
        msgScroll = 0;
        mode = NORMAL;
        be.setViewing(key);
    }

    public void onClosed() { be.setViewing(null); }

    public void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    public String inputHint() {
        return switch (mode) {
            case ADDING -> "Paste a friend code (npub1...)";
            case GROUPING -> "Group name";
            default -> conv == null ? "Pick a chat on the left" : "Message";
        };
    }

    public boolean inputEnabled() { return mode != NORMAL || conv != null; }

    /** The text box's rectangle for the current layout: {x0, y0, x1, y1}. */
    public int[] inputRect() { return new int[]{ix0, iy0, ix1, iy1}; }

    private String input = "";
    private boolean clearRequested;

    /** The screen tells the UI what is in the text box so SEND / CREATE buttons can act on it. */
    public void setInputText(String text) { input = text == null ? "" : text; }

    /** True once after an action used up the text box; the screen then empties it. */
    public boolean takeClear() {
        boolean c = clearRequested;
        clearRequested = false;
        return c;
    }

    /** Enter pressed in the text box (or SEND / CREATE clicked). */
    public void submit(String text) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) return;
        clearRequested = true;
        switch (mode) {
            case ADDING -> {
                String err = be.addFriend(s);
                if (err != null) { say(err); clearRequested = false; }
                else {
                    mode = NORMAL;
                    say("Friend added. They will see your request.");
                }
            }
            case GROUPING -> createGroup(s);
            default -> {
                if (conv != null) {
                    be.sendText(conv, s);
                    msgScroll = 0;
                }
            }
        }
    }

    private void createGroup(String name) {
        if (picked.isEmpty()) {
            say("Tick at least one friend first.");
            clearRequested = false;
            return;
        }
        be.createGroup(name, new ArrayList<>(picked));
        picked.clear();
        mode = NORMAL;
        tab = TAB_GROUPS;
        say("Group created.");
    }

    public boolean textConsumed() { return mode != NORMAL; }

    public void scroll(double dy, boolean overSidebarHint) {
        if (overSidebarHint) sideScroll = Math.max(0, sideScroll - (int) Math.signum(dy) * ROW_H);
        else msgScroll = Math.max(0, msgScroll + (int) Math.signum(dy) * LINE * 2);
    }

    // =========================================================== layout

    private Region add(int id, int arg, int x0, int y0, int x1, int y1, int style, String label, Runs icon) {
        Region r = new Region();
        r.id = id; r.arg = arg; r.x0 = x0; r.y0 = y0; r.x1 = x1; r.y1 = y1; r.style = style;
        r.label = label == null ? "" : label;
        r.icon = icon;
        regions.add(r);
        return r;
    }

    /** Rebuilds the region list for the current state; the screen calls this every frame and syncs its buttons. */
    public List<Region> layout(PixelSink g, int sw, int sh) {
        regions.clear();
        panelX0 = Math.max(2, (sw - 440) / 2);
        panelX1 = sw - panelX0;
        panelY0 = Math.max(2, (sh - 260) / 2);
        panelY1 = sh - panelY0;
        int side = Math.min(120, Math.max(84, (panelX1 - panelX0) / 3));
        int top = panelY0 + 16;
        add(CLOSE, 0, panelX1 - 14, panelY0 + 3, panelX1 - 3, panelY0 + 14, DANGER, "", I_X);

        // ---- sidebar
        Snapshot s = be.snapshot();
        int tw = (side - 6) / 3;
        int tx = panelX0 + 3;
        add(TAB_FRIENDS, 0, tx, top + 3, tx + tw, top + 15, tab == TAB_FRIENDS ? SELECTED : PLAIN, "FRIENDS", null);
        add(TAB_GROUPS, 0, tx + tw, top + 3, tx + 2 * tw, top + 15, tab == TAB_GROUPS ? SELECTED : PLAIN, "GROUPS", null);
        Region rq = add(TAB_REQ, 0, tx + 2 * tw, top + 3, tx + 3 * tw, top + 15, tab == TAB_REQ ? SELECTED : PLAIN, "REQ", null);
        rq.badge = s.requests().size();
        sx0 = panelX0 + 3;
        sx1 = panelX0 + side;
        sy0 = top + 18;
        sy1 = panelY1 - 40;
        int by = panelY1 - 37;
        add(ADD_FRIEND, 0, sx0, by, sx0 + (side - 6) / 2, by + 14, mode == ADDING ? SELECTED : PLAIN, "+ FRIEND", null);
        add(NEW_GROUP, 0, sx0 + (side - 6) / 2 + 2, by, sx1 - 3, by + 14, mode == GROUPING ? SELECTED : PLAIN, "+ GROUP", null);
        add(COPY_CODE, 0, sx0, by + 16, sx1 - 3, by + 30, PLAIN, "COPY MY CODE", null);
        int rows = (sy1 - sy0) / ROW_H;
        if (mode == GROUPING) {
            List<FriendView> fs = s.friends();
            clampSide(fs.size(), rows);
            for (int i = 0; i < rows && i + sideScroll / ROW_H < fs.size(); i++) {
                FriendView f = fs.get(i + sideScroll / ROW_H);
                Region r = add(TOGGLE, i + sideScroll / ROW_H, sx0, sy0 + i * ROW_H, sx1 - 3, sy0 + i * ROW_H + ROW_H - 2, LIST, "", null);
                r.title = f.name();
                r.on = picked.contains(f.hex());
                r.dot = f.presence();
            }
        } else if (tab == TAB_FRIENDS) {
            List<FriendView> fs = s.friends();
            clampSide(fs.size(), rows);
            for (int i = 0; i < rows && i + sideScroll / ROW_H < fs.size(); i++) {
                int idx = i + sideScroll / ROW_H;
                FriendView f = fs.get(idx);
                Region r = add(ROW, idx, sx0, sy0 + i * ROW_H, sx1 - 3, sy0 + i * ROW_H + ROW_H - 2, ("dm:" + f.hex()).equals(conv) ? SELECTED : LIST, "", null);
                r.title = f.name(); r.sub = f.last(); r.dot = f.presence(); r.badge = f.unread();
            }
        } else if (tab == TAB_GROUPS) {
            List<GroupView> gs = s.groups();
            clampSide(gs.size(), rows);
            for (int i = 0; i < rows && i + sideScroll / ROW_H < gs.size(); i++) {
                int idx = i + sideScroll / ROW_H;
                GroupView gv = gs.get(idx);
                Region r = add(ROW, idx, sx0, sy0 + i * ROW_H, sx1 - 3, sy0 + i * ROW_H + ROW_H - 2, ("g:" + gv.id()).equals(conv) ? SELECTED : LIST, "", null);
                r.title = gv.name(); r.sub = gv.last().isEmpty() ? gv.members() + " members" : gv.last(); r.badge = gv.unread();
            }
        } else {
            List<ReqView> rs = s.requests();
            int per = 2 * ROW_H;
            int rrows = Math.max(1, (sy1 - sy0) / per);
            clampSide(rs.size() * 2, rrows * 2);
            for (int i = 0; i < rrows && i + sideScroll / per < rs.size(); i++) {
                int idx = i + sideScroll / per;
                ReqView rv = rs.get(idx);
                int y = sy0 + i * per;
                Region r = add(ROW, 1000 + idx, sx0, y, sx1 - 3, y + ROW_H - 2, LIST, "", null);
                r.title = rv.name(); r.sub = "#" + rv.tag() + " wants to chat";
                int half = (sx1 - 3 - sx0) / 2;
                add(ACCEPT, idx, sx0, y + ROW_H, sx0 + half - 1, y + per - 3, PRIMARY, "ACCEPT", null);
                add(BLOCK, idx, sx0 + half + 1, y + ROW_H, sx1 - 3, y + per - 3, DANGER, "BLOCK", null);
            }
        }

        // ---- conversation
        int cx0 = panelX0 + side + 5, cx1 = panelX1 - 3;
        mx0 = cx0; mx1 = cx1; my0 = top + 18;
        int bar = panelY1 - 20;
        my1 = bar - 12; // leaves a status line above the input bar
        iy0 = bar; iy1 = bar + 16;
        boolean recording = be.recordingMs() >= 0;
        int bx = cx0;
        if (mode == NORMAL) {
            add(ATTACH, 0, bx, bar, bx + 18, bar + 16, PLAIN, "", I_CLIP);
            add(MIC, 0, bx + 20, bar, bx + 38, bar + 16, recording ? DANGER : PLAIN, "", recording ? I_STOP : I_MIC);
            ix0 = bx + 41;
        } else {
            add(CANCEL, 0, bx, bar, bx + 18, bar + 16, DANGER, "", I_X);
            ix0 = bx + 21;
        }
        int sendW = mode == GROUPING ? 54 : 18;
        add(mode == GROUPING ? CREATE : SEND, 0, cx1 - sendW, bar, cx1, bar + 16, PRIMARY, mode == GROUPING ? "CREATE" : "", mode == GROUPING ? null : I_SEND);
        ix1 = cx1 - sendW - 3;
        if (mode == NORMAL && conv != null) {
            if (conv.startsWith("dm:")) add(REMOVE, 0, cx1 - 44, top + 3, cx1, top + 15, DANGER, "UNFRIEND", null);
            else add(LEAVE, 0, cx1 - 36, top + 3, cx1, top + 15, DANGER, "LEAVE", null);
            layoutMessages(g);
        }
        return regions;
    }

    private void clampSide(int count, int rows) {
        int max = Math.max(0, (count - rows) * ROW_H);
        if (max == 0 || count < 1) sideScroll = 0;
        sideScroll = Math.min(sideScroll, max);
        sideScroll -= sideScroll % ROW_H;
    }

    // ---- messages: height bookkeeping shared by layout (action buttons) and paint
    private record Placed(Msg m, int y, int h, int bw, String[] lines) { }

    private final List<Placed> placed = new ArrayList<>();

    private String[] wrap(PixelSink g, Msg m, int maxW) {
        if (m.text().isEmpty()) return new String[0];
        String key = m.id() + (m.mine() ? "m" : "t");
        String[] cached = wrapCache.get(key);
        if (cached != null) return cached;
        List<String> out = new ArrayList<>();
        for (String para : m.text().split("\n", -1)) {
            StringBuilder cur = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                while (g.textWidth(word) > maxW && word.length() > 1) { // break very long words
                    int cut = word.length();
                    while (cut > 1 && g.textWidth(word.substring(0, cut)) > maxW) cut--;
                    if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String tryLine = cur.length() == 0 ? word : cur + " " + word;
                if (g.textWidth(tryLine) <= maxW) { cur.setLength(0); cur.append(tryLine); }
                else { out.add(cur.toString()); cur.setLength(0); cur.append(word); }
            }
            out.add(cur.toString());
        }
        String[] arr = out.toArray(new String[0]);
        if (wrapCache.size() > 600) wrapCache.clear();
        wrapCache.put(key, arr);
        return arr;
    }

    private static int[] thumb(Media md) {
        int w = md.w() > 0 ? md.w() : 4, h = md.h() > 0 ? md.h() : 3;
        double k = Math.min(150.0 / w, 100.0 / h);
        k = Math.min(k, 3.0);
        return new int[]{Math.max(24, (int) Math.round(w * k)), Math.max(18, (int) Math.round(h * k))};
    }

    private void layoutMessages(PixelSink g) {
        placed.clear();
        int areaW = mx1 - mx0, areaH = my1 - my0;
        int maxBubble = Math.max(60, areaW * 3 / 4);
        if (areaW != wrapWidth) { wrapWidth = areaW; wrapCache.clear(); }
        List<Msg> msgs = be.messages(conv);
        boolean group = conv.startsWith("g:");
        int total = 0;
        int[] hs = new int[msgs.size()];
        String[][] ls = new String[msgs.size()][];
        int[] ws = new int[msgs.size()];
        for (int i = 0; i < msgs.size(); i++) {
            Msg m = msgs.get(i);
            String[] lines = wrap(g, m, maxBubble - 8);
            int w = 0;
            for (String l : lines) w = Math.max(w, g.textWidth(l));
            int h = 8 + lines.length * LINE;
            if (group && !m.mine()) { h += LINE; w = Math.max(w, g.textWidth(m.fromName())); }
            if (m.media() != null) {
                int[] th = thumb(m.media());
                switch (m.media().type()) {
                    case "image", "gif" -> { h += th[1] + 3; w = Math.max(w, th[0]); }
                    default -> { h += 18; w = Math.max(w, 110); }
                }
                if (m.state() == Models_FAILED) { h += LINE; w = Math.max(w, 80); }
            }
            hs[i] = h; ls[i] = lines; ws[i] = Math.min(maxBubble, w + 8);
            total += h + 4;
        }
        lastMsgTotal = total;
        msgScroll = Math.max(0, Math.min(msgScroll, Math.max(0, total - areaH)));
        int y = my1 + msgScroll; // bottom of the newest message
        for (int i = msgs.size() - 1; i >= 0; i--) {
            int top = y - hs[i];
            if (y > my0 - 200 && top < my1 + 200) placed.add(new Placed(msgs.get(i), top, hs[i], ws[i], ls[i]));
            y = top - 4;
            if (y < my0 - 200) break;
        }
        for (Placed pl : placed) {
            Msg m = pl.m();
            if (m.media() == null || pl.y() + pl.h() < my0 || pl.y() > my1) continue;
            int bx = m.mine() ? mx1 - pl.bw() : mx0;
            String type = m.media().type();
            int idx = be.messages(conv).indexOf(m);
            if (type.equals("voice")) {
                boolean playing = m.id().equals(be.playingId());
                Region r = add(MSG_ACTION, idx, bx + 4, pl.y() + pl.h() - 20, bx + 20, pl.y() + pl.h() - 4, PRIMARY, "", playing ? I_STOP : I_PLAY);
                r.on = playing;
                if (m.state() != Models_SENT) r.style = INVISIBLE;
            } else if (!type.equals("image") && !type.equals("gif")) {
                add(MSG_ACTION, idx, bx + 4, pl.y() + pl.h() - 20, bx + 20, pl.y() + pl.h() - 4, PRIMARY, "", I_SAVE);
            } else {
                add(MSG_ACTION, idx, bx + 4, pl.y() + 4, bx + 4 + thumb(m.media())[0], pl.y() + 4 + thumb(m.media())[1], INVISIBLE, "", null);
            }
        }
    }

    private static final int Models_FAILED = 2, Models_SENT = 0;

    // =========================================================== actions (fired by real button clicks)

    public void press(int index) {
        if (index < 0 || index >= regions.size()) return;
        Region r = regions.get(index);
        Snapshot s = be.snapshot();
        switch (r.id) {
            case CLOSE -> host.close();
            case TAB_FRIENDS, TAB_GROUPS, TAB_REQ -> { tab = r.id; sideScroll = 0; mode = NORMAL; }
            case ROW -> {
                if (r.arg >= 1000) return;
                if (tab == TAB_FRIENDS && r.arg < s.friends().size()) openConversation("dm:" + s.friends().get(r.arg).hex());
                else if (tab == TAB_GROUPS && r.arg < s.groups().size()) openConversation("g:" + s.groups().get(r.arg).id());
            }
            case ADD_FRIEND -> { mode = mode == ADDING ? NORMAL : ADDING; }
            case NEW_GROUP -> { mode = mode == GROUPING ? NORMAL : GROUPING; picked.clear(); tab = TAB_FRIENDS; sideScroll = 0; }
            case COPY_CODE -> { host.copy(s.myCode()); say("Your friend code was copied. Send it to a friend."); }
            case TOGGLE -> {
                if (r.arg < s.friends().size()) {
                    String hex = s.friends().get(r.arg).hex();
                    if (!picked.remove(hex)) picked.add(hex);
                }
            }
            case CREATE -> onCreateRequested(input);
            case CANCEL -> mode = NORMAL;
            case SEND -> submit(input);
            case ATTACH -> { if (conv != null) { String c = conv; host.pickFile(f -> be.sendFile(c, f)); } }
            case MIC -> {
                if (conv == null) return;
                if (be.recordingMs() >= 0) be.stopRecordingAndSend(conv);
                else be.startRecording();
            }
            case ACCEPT -> { if (r.arg < s.requests().size()) be.acceptRequest(s.requests().get(r.arg).hex()); }
            case BLOCK -> { if (r.arg < s.requests().size()) be.blockRequest(s.requests().get(r.arg).hex()); }
            case REMOVE -> { if (conv != null && conv.startsWith("dm:")) { be.removeFriend(conv.substring(3)); conv = null; } }
            case LEAVE -> { if (conv != null && conv.startsWith("g:")) { be.leaveGroup(conv.substring(2)); conv = null; } }
            case MSG_ACTION -> {
                List<Msg> msgs = conv == null ? List.of() : be.messages(conv);
                if (r.arg < 0 || r.arg >= msgs.size()) return;
                Msg m = msgs.get(r.arg);
                if (m.media() == null) return;
                if (m.media().type().equals("voice")) be.playVoice(m);
                else be.save(m);
            }
            default -> { }
        }
    }

    /** Called on the OK path of the screen's Enter handling in group mode. */
    public void onCreateRequested(String name) { clearRequested = true; createGroup(name.isBlank() ? "Group" : name.strip()); }

    // =========================================================== painting

    public void render(PixelSink g, int sw, int sh, int mouseX, int mouseY, long nowMs) {
        g.fill(0, 0, sw, sh, 0xB0000000);
        notched(g, panelX0, panelY0, panelX1, panelY1, t.out, t.bg, t.bgHi, t.bgLo);
        g.fill(panelX0 + 2, panelY0 + 2, panelX1 - 2, panelY0 + 16, t.bar);
        g.fill(panelX0 + 2, panelY0 + 2, panelX1 - 2, panelY0 + 3, t.barHi);
        g.fill(panelX0 + 2, panelY0 + 15, panelX1 - 2, panelY0 + 16, t.barLo);
        label(g, "FRIEND CHAT", panelX0 + 20, panelY0 + 6, t.txt);
        drawRuns(g, I_BUBBLE, panelX0 + 7, panelY0 + 5, 1, t.acc);
        Snapshot s = be.snapshot();
        String relays = s.relaysUp() + "/" + s.relaysTotal() + " RELAYS";
        int rw = labelWidth(relays);
        label(g, relays, panelX1 - 20 - rw, panelY0 + 6, s.relaysUp() > 0 ? t.acc : 0xFFE0544A);
        g.fill(panelX0 + side() , panelY0 + 17, panelX0 + side() + 1, panelY1 - 3, t.out); // divider

        if (mode == GROUPING) label(g, "TICK FRIENDS", sx0 + 2, sy0 - 9, t.dim);
        if (conv == null && mode == NORMAL) {
            String a = "PICK A FRIEND OR GROUP", b = s.friends().isEmpty() ? "USE + FRIEND AND PASTE A CODE" : "TO START CHATTING";
            label(g, a, (mx0 + mx1 - labelWidth(a)) / 2, (my0 + my1) / 2 - 6, t.dim);
            label(g, b, (mx0 + mx1 - labelWidth(b)) / 2, (my0 + my1) / 2 + 4, t.muted);
        } else if (mode == ADDING) {
            label(g, "ADD A FRIEND", mx0 + 4, my0 + 2, t.acc);
            g.text("Ask them for their friend code (npub1...) and paste it below.", mx0 + 4, my0 + 16, t.dim, mx0, mx1);
            g.text("Your own code is shown with COPY MY CODE.", mx0 + 4, my0 + 28, t.muted, mx0, mx1);
        } else if (mode == GROUPING) {
            label(g, "NEW GROUP", mx0 + 4, my0 + 2, t.acc);
            g.text(picked.size() + " friend(s) ticked. Name the group below.", mx0 + 4, my0 + 16, t.dim, mx0, mx1);
        } else if (conv != null) {
            String name = be.title(conv).toUpperCase(java.util.Locale.ROOT);
            Runs rr = labelRuns(name.length() > 22 ? name.substring(0, 22) : name);
            if (rr.fallback) g.text(be.title(conv), mx0 + 3, panelY0 + 19, t.txt, mx0, mx1 - 50);
            else label(g, name.length() > 22 ? name.substring(0, 22) : name, mx0 + 3, panelY0 + 23, t.txt);
            paintMessages(g, nowMs);
        }
        int rec = be.recordingMs();
        if (rec >= 0) {
            String txt = "RECORDING " + (rec / 1000) + "S - PRESS MIC TO SEND";
            label(g, txt, mx1 - labelWidth(txt) - 2, iy0 - 8, 0xFFFF6B6B);
        }
        for (Region r : regions) paintRegion(g, r, mouseX, mouseY);
        // input box art (the text itself is the game's text field on top)
        notched(g, ix0 - 2, iy0, ix1 + 2, iy1, t.out, t.track, t.bgLo, t.track);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil)
            g.text(status, mx0 + 2, iy0 - 10, t.amb, mx0, mx1);
    }

    private int side() { return sx1 - panelX0 + 2; }

    private void paintRegion(PixelSink g, Region r, int mx, int my) {
        if (r.style == INVISIBLE) return;
        boolean hot = mx >= r.x0 && mx < r.x1 && my >= r.y0 && my < r.y1;
        if (r.style == LIST || (r.style == SELECTED && r.id == ROW)) {
            int fill = r.style == SELECTED ? t.barLo : hot ? t.btnHi : t.btn;
            notched(g, r.x0, r.y0, r.x1, r.y1, t.out, fill, hot || r.style == SELECTED ? t.barHi : t.btnHi, t.btnLo);
            int x = r.x0 + 4;
            if (r.id == TOGGLE) {
                g.fill(x, r.y0 + 5, x + 9, r.y0 + 14, t.out);
                g.fill(x + 1, r.y0 + 6, x + 8, r.y0 + 13, r.on ? t.acc : t.track);
                if (r.on) drawRuns(g, I_CHECK, x + 2, r.y0 + 7, 1, t.out);
                x += 13;
            }
            if (r.dot >= 0) {
                int c = r.dot == 2 ? t.acc : r.dot == 1 ? t.amb : t.muted;
                g.fill(x, r.y0 + 4, x + 5, r.y0 + 9, t.out);
                g.fill(x + 1, r.y0 + 5, x + 4, r.y0 + 8, c);
                x += 8;
            }
            int right = r.x1 - 4 - (r.badge > 0 ? 14 : 0);
            g.text(r.title, x, r.y0 + (r.sub.isEmpty() ? 6 : 2), t.txt, x, right);
            if (!r.sub.isEmpty()) g.text(r.sub.replace('\n', ' '), x, r.y0 + 11, t.dim, x, right);
            if (r.badge > 0) badge(g, r.x1 - 14, r.y0 + 4, r.badge);
            return;
        }
        int base = switch (r.style) {
            case PRIMARY -> t.acc;
            case DANGER -> 0xFFB5413A;
            case SELECTED -> t.bar;
            default -> t.btn;
        };
        int fill = hot ? Theme_mix(base, 0xFFFFFFFF, 0.12f) : base;
        int hi = Theme_mix(fill, 0xFFFFFFFF, 0.25f), lo = Theme_mix(fill, 0xFF000000, 0.35f);
        notched(g, r.x0, r.y0, r.x1, r.y1, t.out, fill, hi, lo);
        int ink = r.style == PRIMARY ? t.out : t.txt;
        if (r.icon != null) drawRuns(g, r.icon, (r.x0 + r.x1 - r.icon.w) / 2, (r.y0 + r.y1 - r.icon.h) / 2, 1, ink);
        if (!r.label.isEmpty()) label(g, r.label, (r.x0 + r.x1 - labelWidth(r.label)) / 2, (r.y0 + r.y1 - 5) / 2, ink);
        if (r.badge > 0) badge(g, r.x1 - 7, r.y0 - 3, r.badge);
    }

    private void badge(PixelSink g, int x, int y, int n) {
        String txt = n > 9 ? "9+" : Integer.toString(n);
        int w = labelWidth(txt) + 4;
        g.fill(x - 1, y - 1, x + w + 1, y + 9, t.out);
        g.fill(x, y, x + w, y + 8, 0xFFE0544A);
        label(g, txt, x + 2, y + 2, 0xFFFFFFFF);
    }

    private void paintMessages(PixelSink g, long nowMs) {
        g.fill(mx0, my0, mx1, my1, t.track);
        for (Placed pl : placed) {
            if (pl.y() + pl.h() < my0 || pl.y() > my1) continue;
            Msg m = pl.m();
            int bx = m.mine() ? mx1 - pl.bw() - 2 : mx0 + 2;
            int x1 = bx + pl.bw();
            int y0 = pl.y(), y1 = pl.y() + pl.h();
            int fill = m.mine() ? Theme_mix(t.bar, t.bg, 0.2f) : t.btn;
            if (y0 < my0 || y1 > my1) {
                // partially visible: clip by drawing only inside the message area using scissor-less trimming
                if (y1 <= my0 + 2 || y0 >= my1 - 2) continue;
            }
            g.fill(bx, Math.max(my0, y0), x1, Math.min(my1, y1), t.out);
            g.fill(bx + 1, Math.max(my0, y0 + 1), x1 - 1, Math.min(my1, y1 - 1), fill);
            int ty = y0 + 4;
            if (conv.startsWith("g:") && !m.mine()) {
                if (ty >= my0 && ty + 9 <= my1) g.text(m.fromName(), bx + 4, ty, t.acc, bx + 4, x1 - 2);
                ty += LINE;
            }
            for (String line : pl.lines()) {
                if (ty >= my0 && ty + 9 <= my1) g.text(line, bx + 4, ty, t.txt, bx + 4, x1 - 2);
                ty += LINE;
            }
            if (m.media() != null) paintMedia(g, m, bx, ty, x1, y1);
        }
        // fade top/bottom edges of the viewport into the panel
        if (msgScroll > 0) {
            String more = "NEW BELOW - SCROLL DOWN";
            label(g, more, mx1 - labelWidth(more) - 4, my1 - 8, t.amb);
        }
    }

    private void paintMedia(PixelSink g, Msg m, int bx, int ty, int x1, int y1) {
        Media md = m.media();
        String type = md.type();
        if (type.equals("image") || type.equals("gif")) {
            int[] th = thumb(md);
            int ix = bx + 4, iy = ty;
            if (iy < my0 || iy + th[1] > my1) return;
            BufferedImage img = be.image(m);
            g.fill(ix - 1, iy - 1, ix + th[0] + 1, iy + th[1] + 1, t.out);
            if (img != null) g.image(m.id(), img, ix, iy, ix + th[0], iy + th[1]);
            else {
                g.fill(ix, iy, ix + th[0], iy + th[1], t.phBg);
                int st = be.mediaStatus(m);
                String msg = m.state() == 1 ? "SENDING" : st == 3 ? "FAILED" : "LOADING";
                label(g, msg, ix + (th[0] - labelWidth(msg)) / 2, iy + th[1] / 2 - 2, t.dim);
            }
            return;
        }
        int ry = y1 - 20;
        if (ry < my0 || ry + 18 > my1) return;
        String desc;
        if (type.equals("voice")) {
            int sec = Math.max(1, md.ms() / 1000);
            desc = "VOICE NOTE " + (sec / 60) + ":" + String.format("%02d", sec % 60);
            int bars = Math.min(14, 4 + sec / 2);
            for (int i = 0; i < bars; i++) {
                int h = 2 + ((i * 7 + 3) % 6);
                g.fill(bx + 26 + i * 3, ry + 9 - h / 2, bx + 28 + i * 3, ry + 9 + h / 2, m.id().equals(be.playingId()) ? t.acc : t.dim);
            }
            if (m.state() == 1) label(g, "SENDING", bx + 26, ry + 14, t.dim);
        } else {
            String nm = md.name().length() > 18 ? md.name().substring(0, 18) : md.name();
            g.text(nm, bx + 24, ry + 1, t.txt, bx + 24, x1 - 3);
            desc = (md.size() / 1024) + " KB " + type.toUpperCase(java.util.Locale.ROOT);
        }
        if (type.equals("voice")) label(g, desc, bx + 26 + 14 * 3 + 6, ry + 6, t.dim);
        else label(g, desc, bx + 24, ry + 12, t.dim);
        if (m.state() == Models_FAILED) label(g, "FAILED - NOT SENT", bx + 4, y1 - 9, 0xFFFF6B6B);
    }

    // ---- primitives
    private static int Theme_mix(int a, int b, float k) {
        int r = Math.round(((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
        int gg = Math.round(((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
        int bl = Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return 0xFF000000 | (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, gg)) << 8) | Math.max(0, Math.min(255, bl));
    }

    /** PixelTunes-style notched box: outline, fill, light top edge, dark bottom edge. */
    private static void notched(PixelSink g, int x0, int y0, int x1, int y1, int out, int fill, int hi, int lo) {
        g.fill(x0 + 1, y0, x1 - 1, y1, out);
        g.fill(x0, y0 + 1, x1, y1 - 1, out);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill);
        g.fill(x0 + 2, y0 + 1, x1 - 2, y0 + 2, hi);
        g.fill(x0 + 2, y1 - 2, x1 - 2, y1 - 1, lo);
    }

    private Runs labelRuns(String s) {
        Runs r = labelCache.get(s);
        if (r == null) {
            if (labelCache.size() > 400) labelCache.clear();
            r = PixelFont.layout(s);
            labelCache.put(s, r);
        }
        return r;
    }

    private int labelWidth(String s) { return labelRuns(s).w; }

    private void label(PixelSink g, String s, int x, int y, int color) { drawRuns(g, labelRuns(s), x, y, 1, color); }

    private static void drawRuns(PixelSink g, Runs r, int x, int y, int scale, int color) {
        for (int i = 0; i < r.data.length; i += 3)
            g.fill(x + r.data[i] * scale, y + r.data[i + 1] * scale, x + (r.data[i] + r.data[i + 2]) * scale, y + (r.data[i + 1] + 1) * scale, color);
    }

    public boolean overSidebar(double mx) { return mx < sx1 + 2; }

    int lastTotal() { return lastMsgTotal; }
}

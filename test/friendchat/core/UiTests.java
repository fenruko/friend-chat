package friendchat.core;

import friendchat.core.chat.ChatBackend;
import friendchat.core.chat.Models.*;
import friendchat.core.ui.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Renders the real UI into PNGs with a fake backend, and checks that button presses do the right thing. */
public final class UiTests {
    static final class Fake implements ChatBackend {
        List<String> log = new ArrayList<>();
        List<Msg> msgs = new ArrayList<>();
        boolean recording;

        Fake() {
            msgs.add(new Msg("1", 1, "aa", "Alex", false, "hey, are you on tonight?", null, 0));
            msgs.add(new Msg("2", 2, "me", "Me", true, "yes! building the farm. come see it later", null, 0));
            msgs.add(new Msg("3", 3, "aa", "Alex", false, "", new Media("voice", "u", "k", "s", "c", "audio/x-fcopus", "v.fcv", 0, 0, 12000, 9000), 0));
            msgs.add(new Msg("4", 4, "me", "Me", true, "", new Media("image", "u", "k", "s", "c", "image/png", "farm.png", 640, 360, 0, 90000), 0));
            msgs.add(new Msg("5", 5, "aa", "Alex", false, "", new Media("video", "u", "k", "s", "c", "video/mp4", "clip.mp4", 1280, 720, 5000, 4_000_000), 0));
        }

        public Snapshot snapshot() {
            return new Snapshot(List.of(new FriendView("aa", "Alex", "aa11", 2, 2, "voice note"), new FriendView("bb", "Sam", "bb22", 1, 0, "see you"),
                    new FriendView("cc", "محمد", "cc33", 0, 0, "")), List.of(new GroupView("g1", "SMP Crew", 4, 0, "gg")),
                    List.of(new ReqView("dd", "Newbie", "dd44")), 3, 3, "Me", "ff00", "npub1xyz");
        }
        public int version() { return 0; }
        public List<Msg> messages(String k) { return msgs; }
        public String title(String k) { return "Alex"; }
        public void setViewing(String k) { log.add("view " + k); }
        public String addFriend(String c) { log.add("add " + c); return c.startsWith("npub1") ? null : "bad code"; }
        public void acceptRequest(String h) { log.add("accept " + h); }
        public void blockRequest(String h) { log.add("block " + h); }
        public void removeFriend(String h) { log.add("remove " + h); }
        public void createGroup(String n, List<String> m) { log.add("group " + n + m); }
        public void leaveGroup(String i) { log.add("leave " + i); }
        public void sendText(String c, String t) { log.add("send " + c + " " + t); }
        public void sendFile(String c, File f) { log.add("file " + c); }
        public void startRecording() { recording = true; log.add("rec"); }
        public void stopRecordingAndSend(String c) { recording = false; log.add("recsend"); }
        public void cancelRecording() { }
        public int recordingMs() { return recording ? 3000 : -1; }
        public void playVoice(Msg m) { log.add("play " + m.id()); }
        public String playingId() { return ""; }
        public int mediaStatus(Msg m) { return 3; }
        public BufferedImage image(Msg m) { return null; }
        public void save(Msg m) { log.add("save " + m.id()); }
    }

    static final class PngSink implements PixelSink {
        final BufferedImage img;
        final Graphics2D g;
        PngSink(int w, int h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            g = img.createGraphics();
            g.setColor(new Color(40, 70, 40));
            g.fillRect(0, 0, w, h);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        }
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            int a = argb >>> 24;
            g.setColor(new Color(argb, true));
            if (a != 255) g.setComposite(java.awt.AlphaComposite.SrcOver);
            g.fillRect(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        }
        public void text(String s, int x, int y, int argb, int c0, int c1) {
            java.awt.Shape old = g.getClip();
            g.clipRect(c0, y - 2, Math.max(0, c1 - c0), 14);
            g.setColor(new Color(argb, true));
            g.drawString(s, x, y + 7);
            g.setClip(old);
        }
        public int textWidth(String s) { return g.getFontMetrics().stringWidth(s); }
        public void image(String k, BufferedImage i, int x0, int y0, int x1, int y1) { g.drawImage(i, x0, y0, x1 - x0, y1 - y0, null); }
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Fake be = new Fake();
        List<String> hostLog = new ArrayList<>();
        ChatUi ui = new ChatUi(be, new ChatUi.Host() {
            public void close() { hostLog.add("close"); }
            public void copy(String t) { hostLog.add("copy " + t); }
            public void pickFile(java.util.function.Consumer<File> c) { c.accept(new File("x.png")); }
        });
        ui.setTheme("PURPLE", 1);
        int w = 480, h = 270;
        File outDir = new File(a.length > 0 ? a[0] : "gallery");
        outDir.mkdirs();

        PngSink s = new PngSink(w, h);
        List<ChatUi.Region> rs = ui.layout(s, w, h);
        T.ok("regions exist", rs.size() > 8);
        int rowIdx = -1, sendIdx = -1, micIdx = -1, addIdx = -1, groupIdx = -1, tabReq = -1, closeIdx = -1, copyIdx = -1;
        for (int i = 0; i < rs.size(); i++) {
            ChatUi.Region r = rs.get(i);
            if (r.id == ChatUi.ROW && rowIdx < 0) rowIdx = i;
            if (r.id == ChatUi.MIC) micIdx = i;
            if (r.id == ChatUi.ADD_FRIEND) addIdx = i;
            if (r.id == ChatUi.NEW_GROUP) groupIdx = i;
            if (r.id == ChatUi.TAB_REQ) tabReq = i;
            if (r.id == ChatUi.CLOSE) closeIdx = i;
            if (r.id == ChatUi.COPY_CODE) copyIdx = i;
            T.ok("region inside screen " + i, r.x0 >= 0 && r.y0 >= 0 && r.x1 <= w && r.y1 <= h && r.x1 > r.x0 && r.y1 > r.y0);
        }
        ui.render(s, w, h, -1, -1, 0);
        ImageIO.write(s.img, "png", new File(outDir, "empty.png"));

        ui.press(rowIdx);
        T.eq("open dm", "dm:aa", ui.conversation());
        s = new PngSink(w, h);
        rs = ui.layout(s, w, h);
        ui.render(s, w, h, 200, 120, 0);
        ImageIO.write(s.img, "png", new File(outDir, "dm.png"));
        boolean hasPlay = false, hasSave = false;
        for (int i = 0; i < rs.size(); i++) {
            ChatUi.Region r = rs.get(i);
            if (r.id == ChatUi.MSG_ACTION) {
                ui.press(i);
            }
        }
        T.ok("voice play fired", be.log.contains("play 3"));
        T.ok("video save fired", be.log.contains("save 5"));

        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.MIC) micIdx = i;
        ui.press(micIdx);
        T.ok("mic starts", be.recording);
        s = new PngSink(w, h);
        rs = ui.layout(s, w, h);
        ui.render(s, w, h, -1, -1, 0);
        ImageIO.write(s.img, "png", new File(outDir, "recording.png"));
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.MIC) ui.press(i);
        T.ok("mic stops and sends", be.log.contains("recsend"));

        ui.submit("hello there");
        T.ok("send text", be.log.contains("send dm:aa hello there"));
        T.ok("input cleared", ui.takeClear());

        ui.layout(s, w, h);
        ui.press(addIdx);
        T.eq("adding mode", ChatUi.ADDING, ui.mode());
        ui.submit("garbage");
        T.ok("bad code keeps text", !ui.takeClear());
        ui.submit("npub1abc");
        T.eq("back to normal", ChatUi.NORMAL, ui.mode());

        rs = ui.layout(s, w, h);
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.NEW_GROUP) ui.press(i);
        T.eq("grouping mode", ChatUi.GROUPING, ui.mode());
        rs = ui.layout(s, w, h);
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.TOGGLE && rs.get(i).arg == 0) ui.press(i);
        s = new PngSink(w, h);
        rs = ui.layout(s, w, h);
        ui.render(s, w, h, -1, -1, 0);
        ImageIO.write(s.img, "png", new File(outDir, "group.png"));
        ui.setInputText("Crew");
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.CREATE) ui.press(i);
        T.ok("group created", be.log.stream().anyMatch(x -> x.startsWith("group Crew[aa]")));

        rs = ui.layout(s, w, h);
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.TAB_REQ) ui.press(i);
        rs = ui.layout(s, w, h);
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.ACCEPT) ui.press(i);
        T.ok("accept request", be.log.contains("accept dd"));
        s = new PngSink(w, h);
        rs = ui.layout(s, w, h);
        ui.render(s, w, h, -1, -1, 0);
        ImageIO.write(s.img, "png", new File(outDir, "requests.png"));

        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.COPY_CODE) ui.press(i);
        T.ok("copy code", hostLog.contains("copy npub1xyz"));
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).id == ChatUi.CLOSE) ui.press(i);
        T.ok("close", hostLog.contains("close"));

        // small screen must still lay out inside the window
        PngSink sm = new PngSink(320, 180);
        for (ChatUi.Region r : ui.layout(sm, 320, 180)) T.ok("small region " + r.id, r.x1 <= 320 && r.y1 <= 180 && r.x0 >= 0 && r.y0 >= 0);
        ui.render(sm, 320, 180, -1, -1, 0);
        ImageIO.write(sm.img, "png", new File(outDir, "small.png"));

        // toasts
        Toasts t = new Toasts();
        t.push("Alex", "hey, are you on tonight? this is a very long message that must be trimmed nicely", Toasts.MESSAGE);
        t.push("Upload failed", "relay said no", Toasts.ERROR);
        PngSink ts = new PngSink(w, h);
        t.render(ts, Theme.preset("PURPLE"), w, h, 1, 100);
        t.render(ts, Theme.preset("PURPLE"), w, h, 1, 1000);
        ImageIO.write(ts.img, "png", new File(outDir, "toasts.png"));
        T.done("UiTests");
    }
}

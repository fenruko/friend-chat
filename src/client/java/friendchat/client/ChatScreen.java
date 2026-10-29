package friendchat.client;

import friendchat.core.chat.ChatBackend;
import friendchat.core.ui.ChatUi;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The game-side shell of the chat window. Every clickable area of {@link ChatUi} becomes a real (invisible) button, so
 * clicks arrive through the game's own widget system, the way PixelTunes' settings screen and the Mod Menu list work.
 * The only other widget is the game's own text box.
 */
final class ChatScreen extends Screen {
    private final Screen parent;
    private final ChatBackend backend;
    private final ChatUi ui;
    private final McSink sink = new McSink();
    private final List<RegionButton> pool = new ArrayList<>();
    private EditBox input;
    private int seenVersion = -1;
    private String lastMode = "";

    ChatScreen(Screen parent, ChatBackend backend) {
        super(Component.literal("Friend Chat"));
        this.parent = parent;
        this.backend = backend;
        this.ui = new ChatUi(backend, new ChatUi.Host() {
            @Override public void close() { onClose(); }

            @Override public void copy(String text) { McClipboard.write(text); }

            @Override public void pickFile(Consumer<File> then) { FilePicker.pick(then); }
        });
        applyConfig();
    }

    private void applyConfig() {
        ui.setTheme(FriendChatClient.config().theme, FriendChatClient.config().pixelSize);
    }

    @Override
    protected void init() {
        pool.clear();
        input = new EditBox(this.font, 0, 0, 100, 12, Component.empty());
        input.setBordered(false);
        input.setMaxLength(2000);
        addWidget(input); // takes part in typing/focus, but we draw it ourselves on top of the pixel panel
        setFocused(input);
        input.setFocused(true);
    }

    boolean onKey(Object event) {
        if (!isEnter(event) || input == null) return true;
        String text = input.getValue();
        if (ui.mode() == ChatUi.GROUPING) ui.onCreateRequested(text);
        else ui.submit(text);
        if (ui.takeClear()) input.setValue("");
        return false;
    }

    boolean onScroll(double mouseX, double vertical) {
        ui.scroll(vertical, ui.overSidebar(mouseX));
        return false;
    }

    private static boolean isEnter(Object ev) {
        try {
            Method m = ev.getClass().getMethod("isConfirmation");
            return (boolean) m.invoke(ev);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to key codes
        }
        try {
            int k = (int) ev.getClass().getMethod("key").invoke(ev);
            return k == 13 || k == 257 || k == 335 || k == 0x40000058;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        applyConfig();
        sink.begin(graphics, this.font);
        int w = graphics.guiWidth(), h = graphics.guiHeight();
        // 1) lay out and move the real buttons so the game's click handling sees them
        List<ChatUi.Region> regions = ui.layout(sink, w, h);
        while (pool.size() < regions.size()) {
            RegionButton b = new RegionButton(pool.size(), btn -> ui.press(((RegionButton) btn).index));
            pool.add(b);
            addRenderableWidget(b);
        }
        for (int i = 0; i < pool.size(); i++) {
            RegionButton b = pool.get(i);
            boolean used = i < regions.size();
            if (used) {
                ChatUi.Region r = regions.get(i);
                b.setX(r.x0);
                b.setY(r.y0);
                b.setWidth(r.w());
                b.setHeight(r.h());
            }
            b.visible = used;
            b.active = used;
        }
        // a clicked button must not keep the keyboard: hand it straight back to the text box
        if (getFocused() instanceof RegionButton) setFocused(input);
        // 2) text box follows the layout
        int[] r = ui.inputRect();
        input.setX(r[0] + 3);
        input.setY(r[1] + 4);
        input.setWidth(Math.max(10, r[2] - r[0] - 6));
        input.setHint(Component.literal(ui.inputHint()));
        input.setEditable(ui.inputEnabled());
        ui.setInputText(input.getValue());
        String mode = ui.mode() + "|" + ui.conversation();
        if (!mode.equals(lastMode)) {
            lastMode = mode;
            input.setValue("");
        }
        if (ui.takeClear()) input.setValue("");
        // 3) paint: game background, then our pixel UI, then the text box on top of it
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ui.render(sink, w, h, mouseX, mouseY, System.currentTimeMillis());
        input.extractRenderState(graphics, mouseX, mouseY, partialTick);
        FriendChatClient.drawToasts(graphics);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ui.onClosed();
        sink.close();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}

package friendchat.client;

import com.mojang.blaze3d.platform.InputConstants;
import friendchat.core.chat.ChatEngine;
import friendchat.core.chat.FcConfig;
import friendchat.core.ui.Theme;
import friendchat.core.ui.Toasts;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Wires the Minecraft-free core into the game: the chat engine, the toast layer, the key binding, and the chat
 * buttons on the main menu and the pause menu. The engine lives on its own low-priority thread, so none of this
 * does network or disk work on the game thread.
 */
public final class FriendChatClient implements ClientModInitializer {
    public static final String MOD_ID = "friendchat";
    private static final int SDL_SCANCODE_F6 = 63; // SDL3 scancodes, like PixelTunes

    private static FcConfig cfg = new FcConfig();
    private static Path dir;
    private static ChatEngine engine;
    private static final Toasts TOASTS = new Toasts();
    private static final McSink TOAST_SINK = new McSink();
    private static KeyMapping keyOpen;
    private static int ticks;

    static FcConfig config() { return cfg; }

    static ChatEngine engine() { return engine; }

    @Override
    public void onInitializeClient() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("friend-chat");
        cfg = FcConfig.load(dir.resolve("friend-chat.properties"));
        engine = new ChatEngine(dir, cfg, TOASTS, VoiceOpus::new);
        String name = "";
        try {
            name = Minecraft.getInstance().getUser().getName();
        } catch (RuntimeException ignored) {
            // the name is only a default; it can be changed in the config
        }
        engine.start(name);

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        keyOpen = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.friendchat.open", InputConstants.Type.KEYBOARD, SDL_SCANCODE_F6, cat));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (keyOpen.consumeClick()) openChat(client.gui.screen());
            if (++ticks % 40 == 0) engine.setLocalState(client.level != null ? ChatEngine.IN_GAME : ChatEngine.ONLINE);
        });

        // toasts while playing (no menu open) ...
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "toasts"),
                (graphics, tickCounter) -> {
                    if (Minecraft.getInstance().gui.screen() == null) drawToasts(graphics);
                });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            // ... and on every menu / screen
            if (!(screen instanceof ChatScreen)) ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, tick) -> drawToasts(graphics));

            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                MenuButton button = new MenuButton(4, 4, b -> openChat(screen));
                button.setTooltip(Tooltip.create(Component.literal("Friend Chat")));
                Screens.getWidgets(screen).add(button);
            }
            if (screen instanceof ChatScreen chat) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> chat.onKey(event));
                ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, horizontal, vertical) -> chat.onScroll(mx, vertical));
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            engine.stop();
            TOAST_SINK.close();
        });
    }

    static void openChat(Screen from) {
        Minecraft mc = Minecraft.getInstance();
        if (from instanceof ChatScreen) return;
        mc.gui.setScreen(new ChatScreen(from, engine));
    }

    static void drawToasts(GuiGraphicsExtractor graphics) {
        TOAST_SINK.begin(graphics, Minecraft.getInstance().font);
        TOASTS.render(TOAST_SINK, Theme.preset(cfg.theme), graphics.guiWidth(), graphics.guiHeight(), Math.max(1, Math.min(2, cfg.pixelSize)), System.currentTimeMillis());
    }
}

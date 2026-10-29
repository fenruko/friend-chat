package friendchat.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/**
 * Reads the system clipboard through the game itself. Done by reflection and with fallbacks so that a rename in a
 * future Minecraft version only disables this convenience (the command-line fallback still works) instead of
 * breaking the build. Must be called on the game thread.
 */
final class McClipboard {
    private McClipboard() { }

    /** Copies text to the system clipboard (game thread). */
    static void write(String text) {
        try {
            Minecraft mc = Minecraft.getInstance();
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (!f.getType().getSimpleName().equals("KeyboardHandler")) continue;
                f.setAccessible(true);
                Object kh = f.get(mc);
                if (kh == null) continue;
                kh.getClass().getMethod("setClipboard", String.class).invoke(kh, text);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // try SDL
        }
        try {
            Class.forName("org.lwjgl.sdl.SDLClipboard").getMethod("SDL_SetClipboardText", CharSequence.class).invoke(null, text);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // clipboard unavailable: the code is still shown in chat
        }
    }

    static String read() {
        String s = viaKeyboardHandler();
        if (s == null) s = viaSdl();
        return s;
    }

    private static String viaKeyboardHandler() {
        try {
            Minecraft mc = Minecraft.getInstance();
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (!f.getType().getSimpleName().equals("KeyboardHandler")) continue;
                f.setAccessible(true);
                Object kh = f.get(mc);
                if (kh == null) continue;
                Method m = kh.getClass().getMethod("getClipboard");
                Object r = m.invoke(kh);
                return r instanceof String str ? str : null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // not available in this version: try the next way
        }
        return null;
    }

    private static String viaSdl() {
        try {
            Class<?> c = Class.forName("org.lwjgl.sdl.SDLClipboard");
            Object r = c.getMethod("SDL_GetClipboardText").invoke(null);
            return r instanceof String str ? str : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}

package friendchat.client;

import friendchat.core.ui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The little chat-bubble button on the main menu and the pause menu. A normal game Button (so the game handles the
 * click), drawn in the PixelTunes pixel-art style with an unread badge.
 */
final class MenuButton extends Button {
    private static final String[] BUBBLE = {".#####.", "#.....#", "#.....#", "#.....#", ".#####.", "..#....", ".#....."};

    MenuButton(int x, int y, OnPress onPress) {
        super(x, y, 20, 20, Component.literal("Friend Chat"), onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float tickDelta) {
        Theme t = Theme.preset(FriendChatClient.config().theme);
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hot = isHovered();
        int fill = hot ? t.btnHi : t.btn;
        g.fill(x + 1, y, x + w - 1, y + h, t.out);
        g.fill(x, y + 1, x + w, y + h - 1, t.out);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 2, y + 1, x + w - 2, y + 2, t.barHi);
        g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, t.btnLo);
        int ox = x + (w - 7) / 2, oy = y + (h - 7) / 2 - 1;
        int color = FriendChatClient.engine() != null && FriendChatClient.engine().snapshot().relaysUp() > 0 ? t.acc : t.muted;
        for (int row = 0; row < BUBBLE.length; row++)
            for (int col = 0; col < BUBBLE[row].length(); col++)
                if (BUBBLE[row].charAt(col) == '#') g.fill(ox + col, oy + row, ox + col + 1, oy + row + 1, color);
        int unread = FriendChatClient.engine() == null ? 0 : FriendChatClient.engine().totalUnread();
        if (unread > 0) {
            g.fill(x + w - 8, y - 3, x + w + 3, y + 7, t.out);
            g.fill(x + w - 7, y - 2, x + w + 2, y + 6, 0xFFE0544A);
            g.fill(x + w - 4, y, x + w - 1, y + 4, 0xFFFFFFFF);
        }
    }
}

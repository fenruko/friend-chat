package friendchat.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * An invisible real button (same idea as PixelTunes' RegionButton). The pixel-art UI draws everything; these only
 * exist so clicks arrive through the game's own widget system instead of raw mouse coordinates.
 */
final class RegionButton extends Button {
    final int index;

    RegionButton(int index, OnPress onPress) {
        super(0, 0, 1, 1, Component.empty(), onPress, DEFAULT_NARRATION);
        this.index = index;
        this.visible = false;
        this.active = false;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        // intentionally empty
    }
}

package pro.mikey.xray.screens.helpers;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;


public class SupportButton extends Button {
    private List<FormattedText> support = new ArrayList<>();

    public SupportButton(int widthIn, int heightIn, int width, int height, Component text, MutableComponent support, OnPress onPress) {
        super(widthIn, heightIn, width, height, text, onPress, DEFAULT_NARRATION);

        for(String line : support.getString().split("\n")) {
            this.support.add(Component.literal(line));
        }
    }

    // Buttons no longer draw their own label in 1.21.11; this mirrors vanilla Button.Plain
    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderDefaultSprite(guiGraphics);
        this.renderDefaultLabel(guiGraphics.textRendererForWidget(this, GuiGraphics.HoveredTextEffects.NONE));
    }

    public List<FormattedText> getSupport() {
        return support;
    }
}

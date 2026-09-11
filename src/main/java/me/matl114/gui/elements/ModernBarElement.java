package me.matl114.gui.elements;

import me.matl114.gui.basic.*;
import me.matl114.gui.complex.RawTextElement;
import me.matl114.gui.theme.UiColorUtil;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;

/**
 * Window/title bar with a vertical accent gradient and a darker bottom edge,
 * replacing the flat single-color {@link ColorLabelTextElement} look.
 */
public class ModernBarElement extends RawTextElement {
    protected final ColorSampler accentColor;

    public ModernBarElement(TextProvider text, ColorSampler textColor, ColorSampler accentColor) {
        super(text, textColor, 0);
        this.accentColor = accentColor;
    }

    @Override
    public void renderCentered0(
            DrawableWidget element,
            VDrawContext context,
            int mouseX,
            int mouseY,
            float delta,
            float alpha,
            boolean shouldHighlight) {
        int width = element.getTextureWidth();
        int height = element.getTextureHeight();
        int accent = accentColor.getColorInt();
        int top = UiColorUtil.withAlpha(accent, shouldHighlight ? 255 : 235);
        int bottom = UiColorUtil.withAlpha(UiColorUtil.mulRgb(accent, 0.62F), 255);
        context.fillGuiGradient(0, 0, width, height, top, bottom, 0);
        context.fill(0, height - 1, width, height, 0, UiColorUtil.withAlpha(UiColorUtil.mulRgb(accent, 0.42F), 255));
        OrderedText text1 = text.getLabel(element);
        if (text1 != null) {
            RenderHandler.drawScaledText0(
                    context,
                    mc.textRenderer,
                    text1,
                    0,
                    0,
                    width,
                    height,
                    color.getColorInt() | (MathHelper.ceil(alpha * 255.0F) << 24),
                    alignment);
        }
    }
}

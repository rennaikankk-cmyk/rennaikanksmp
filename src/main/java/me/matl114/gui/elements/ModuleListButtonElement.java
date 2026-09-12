package me.matl114.gui.elements;

import java.util.function.BooleanSupplier;
import me.matl114.gui.basic.*;
import me.matl114.gui.complex.BoxElement;
import me.matl114.gui.theme.UiColorUtil;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;

/**
 * Module list entry: subtle vertical gradient body, a white wash on hover and
 * a hairline separator at the bottom. The enable state is shown by the pill
 * switch on the right (green = on, transparent = off) instead of the old 2px
 * accent bar. Replaces the flat {@link ColorBoxElement} look.
 */
public class ModuleListButtonElement extends BoxElement {
    protected final TextProvider text;
    protected final ColorSampler backgroundColor;
    protected final ColorSampler textColor;
    protected final ColorSampler accentColor;
    protected final BooleanSupplier enabled;

    public ModuleListButtonElement(
            ButtonAction action,
            TextProvider text,
            ColorSampler backgroundColor,
            ColorSampler textColor,
            ColorSampler accentColor,
            BooleanSupplier enabled) {
        super(action);
        this.text = text;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.accentColor = accentColor;
        this.enabled = enabled;
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
        int base = backgroundColor.getColorInt();
        int top = UiColorUtil.withAlpha(base, 208);
        int bottom = UiColorUtil.withAlpha(UiColorUtil.mulRgb(base, 0.72F), 235);
        context.fillGuiGradient(0, 0, width, height, top, bottom, 0);
        boolean isEnabled = enabled.getAsBoolean();
        if (shouldHighlight) {
            context.fill(0, 0, width, height, 0, 0x24FFFFFF);
        }
        context.fill(0, height - 1, width, height, 0, 0x14FFFFFF);
        // pill switch on the right carries the on/off state now
        int switchW = Math.min(20, Math.max(14, height));
        ToggleSwitchElement.drawSwitch(context, width - switchW - 3, 0, switchW, height, isEnabled, alpha);
        OrderedText text1 = text.getLabel(element);
        if (text1 != null) {
            RenderHandler.drawScaledText0(
                    context,
                    mc.textRenderer,
                    text1,
                    0,
                    0,
                    width - switchW - 5,
                    height,
                    textColor.getColorInt() | (MathHelper.ceil(alpha * 255.0F) << 24),
                    0);
        }
    }
}

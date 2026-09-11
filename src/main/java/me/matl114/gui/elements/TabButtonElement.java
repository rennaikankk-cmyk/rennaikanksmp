package me.matl114.gui.elements;

import java.util.function.BooleanSupplier;
import me.matl114.gui.basic.*;
import me.matl114.gui.complex.BoxElement;
import me.matl114.versioned.api.VDrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.util.math.MathHelper;

/**
 * Flat top-bar tab for the ClickGui selection bar: selected tab gets a
 * translucent white wash plus a 2px accent underline, hovered tabs a faint
 * wash. Replaces the vanilla textured button row.
 */
public class TabButtonElement extends BoxElement {
    protected final TextProvider text;
    protected final ColorSampler textColor;
    protected final ColorSampler accentColor;
    protected final BooleanSupplier selected;

    public TabButtonElement(
            ButtonAction action,
            TextProvider text,
            ColorSampler textColor,
            ColorSampler accentColor,
            BooleanSupplier selected) {
        super(action);
        this.text = text;
        this.textColor = textColor;
        this.accentColor = accentColor;
        this.selected = selected;
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
        boolean isSelected = selected.getAsBoolean();
        if (isSelected) {
            context.fill(0, 0, width, height, 0, 0x30FFFFFF);
            context.fill(0, height - 2, width, height, 0, accentColor.getColorInt());
        } else if (shouldHighlight) {
            context.fill(0, 0, width, height, 0, 0x1AFFFFFF);
        }
        OrderedText text1 = text.getLabel(element);
        if (text1 != null) {
            int color = (isSelected ? textColor.getColorInt() : 0xFFAEB4C0) | (MathHelper.ceil(alpha * 255.0F) << 24);
            RenderHandler.drawScaledText0(context, mc.textRenderer, text1, 0, 0, width, height, color, 0);
        }
    }
}

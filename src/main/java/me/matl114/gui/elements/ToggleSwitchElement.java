package me.matl114.gui.elements;

import java.util.function.BooleanSupplier;
import me.matl114.gui.basic.ButtonAction;
import me.matl114.gui.basic.DrawableWidget;
import me.matl114.gui.complex.BoxElement;
import me.matl114.gui.theme.UiColorUtil;
import me.matl114.versioned.api.VDrawContext;

/**
 * iOS-style toggle switch for boolean values, replacing the old icon button
 * whose on/off states were near-indistinguishable. On = green pill with the
 * knob on the right; off = transparent outlined pill with the knob on the
 * left.
 */
public class ToggleSwitchElement extends BoxElement {
    protected final BooleanSupplier state;

    public static final int ON_TRACK = 0xFF2EA043; // saturated green, readable on dark GUI
    public static final int ON_KNOB = 0xFFF0F2F4;
    public static final int OFF_TRACK = 0x00000000; // transparent: outline only
    public static final int OFF_BORDER = 0x66FFFFFF;
    public static final int OFF_KNOB = 0x96FFFFFF;

    public ToggleSwitchElement(ButtonAction action, BooleanSupplier state) {
        super(action);
        this.state = state;
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
        drawSwitch(context, 0, 0, element.getTextureWidth(), element.getTextureHeight(), state.getAsBoolean(), alpha);
    }

    /**
     * shared pill-switch painter; safe to call from other elements' render
     * (module list rows paint a non-interactive copy of the same switch)
     */
    public static void drawSwitch(VDrawContext context, int x, int y, int w, int h, boolean on, float alpha) {
        int trackH = Math.max(6, (h * 3) / 5);
        int trackY = y + (h - trackH) / 2;
        int trackX = x + 1;
        int trackW = Math.max(10, w - 2);
        if (on) {
            context.fill(
                    trackX,
                    trackY,
                    trackX + trackW,
                    trackY + trackH,
                    0,
                    UiColorUtil.withAlpha(ON_TRACK, alpha255(alpha)));
            int knob = trackH - 2;
            context.fill(
                    trackX + trackW - knob - 1,
                    trackY + 1,
                    trackX + trackW - 1,
                    trackY + 1 + knob,
                    0,
                    UiColorUtil.withAlpha(ON_KNOB, alpha255(alpha)));
        } else {
            int border = UiColorUtil.withAlpha(OFF_BORDER, alpha255(alpha));
            context.fill(trackX, trackY, trackX + trackW, trackY + 1, 0, border);
            context.fill(trackX, trackY + trackH - 1, trackX + trackW, trackY + trackH, 0, border);
            context.fill(trackX, trackY + 1, trackX + 1, trackY + trackH - 1, 0, border);
            context.fill(trackX + trackW - 1, trackY + 1, trackX + trackW, trackY + trackH - 1, 0, border);
            int knob = trackH - 2;
            context.fill(
                    trackX + 1,
                    trackY + 1,
                    trackX + 1 + knob,
                    trackY + 1 + knob,
                    0,
                    UiColorUtil.withAlpha(OFF_KNOB, alpha255(alpha)));
        }
    }

    private static int alpha255(float alpha) {
        return Math.max(0, Math.min(255, (int) (alpha * 255.0F)));
    }
}

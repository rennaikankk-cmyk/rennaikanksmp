package me.matl114.gui.theme;

/**
 * ARGB int color helpers for the modern GUI styling.
 * Colors in this codebase are plain ARGB ints sampled from config (WrapColor),
 * so the derived shades (gradients, borders) are computed at draw time.
 */
public final class UiColorUtil {
    private UiColorUtil() {}

    public static int withAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static int mulRgb(int color, float factor) {
        return (color & 0xFF000000)
                | (clamp((int) (((color >>> 16) & 0xFF) * factor)) << 16)
                | (clamp((int) (((color >>> 8) & 0xFF) * factor)) << 8)
                | clamp((int) ((color & 0xFF) * factor));
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}

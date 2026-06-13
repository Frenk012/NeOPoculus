/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 * Source: CaffeineMC/Sodium (LGPL-3.0). See ColorU8 for details.
 */
package net.caffeinemc.mods.sodium.api.util;

import java.nio.ByteOrder;

/**
 * Provides some utilities for packing and unpacking color components from packed integer colors in ABGR format, which
 * is used by OpenGL for color vectors.
 */
public class ColorABGR implements ColorU8 {
    private static final int RED_COMPONENT_OFFSET   = 0;
    private static final int GREEN_COMPONENT_OFFSET = 8;
    private static final int BLUE_COMPONENT_OFFSET  = 16;
    private static final int ALPHA_COMPONENT_OFFSET = 24;

    private static final int RED_COMPONENT_MASK     = COMPONENT_MASK << RED_COMPONENT_OFFSET;
    private static final int GREEN_COMPONENT_MASK   = COMPONENT_MASK << GREEN_COMPONENT_OFFSET;
    private static final int BLUE_COMPONENT_MASK    = COMPONENT_MASK << BLUE_COMPONENT_OFFSET;
    private static final int ALPHA_COMPONENT_MASK   = COMPONENT_MASK << ALPHA_COMPONENT_OFFSET;

    public static int pack(float r, float g, float b) {
        return pack(r, g, b, COMPONENT_MASK);
    }

    public static int pack(int r, int g, int b, int a) {
        return ((a & COMPONENT_MASK) << ALPHA_COMPONENT_OFFSET) |
                ((b & COMPONENT_MASK) << BLUE_COMPONENT_OFFSET) |
                ((g & COMPONENT_MASK) << GREEN_COMPONENT_OFFSET) |
                ((r & COMPONENT_MASK) << RED_COMPONENT_OFFSET);
    }

    public static int withAlpha(int rgb, float alpha) {
        return withAlpha(rgb, ColorU8.normalizedFloatToByte(alpha));
    }

    public static int withAlpha(int rgb, int alpha) {
        return (alpha << ALPHA_COMPONENT_OFFSET) | (rgb & ~(COMPONENT_MASK << ALPHA_COMPONENT_OFFSET));
    }

    public static int pack(float r, float g, float b, float a) {
        return pack(ColorU8.normalizedFloatToByte(r),
                ColorU8.normalizedFloatToByte(g),
                ColorU8.normalizedFloatToByte(b),
                ColorU8.normalizedFloatToByte(a));
    }

    public static int unpackRed(int color) {
        return (color >> RED_COMPONENT_OFFSET) & COMPONENT_MASK;
    }

    public static int unpackGreen(int color) {
        return (color >> GREEN_COMPONENT_OFFSET) & COMPONENT_MASK;
    }

    public static int unpackBlue(int color) {
        return (color >> BLUE_COMPONENT_OFFSET) & COMPONENT_MASK;
    }

    public static int unpackAlpha(int color) {
        return (color >> ALPHA_COMPONENT_OFFSET) & COMPONENT_MASK;
    }

    public static int mulRGB(int color, int factor) {
        return (ColorMixer.mul(color, factor) & ~ALPHA_COMPONENT_MASK) | (color & ALPHA_COMPONENT_MASK);
    }

    public static int mulRGB(int color, float factor) {
        return mulRGB(color, ColorU8.normalizedFloatToByte(factor));
    }

    private static final boolean BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

    public static int fromNativeByteOrder(int color) {
        if (BIG_ENDIAN) {
            return Integer.reverseBytes(color);
        } else {
            return color;
        }
    }

    public static int toNativeByteOrder(int color) {
        if (BIG_ENDIAN) {
            return Integer.reverseBytes(color);
        } else {
            return color;
        }
    }
}

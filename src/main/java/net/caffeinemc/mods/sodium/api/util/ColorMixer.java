/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 * Source: CaffeineMC/Sodium (LGPL-3.0). See ColorU8 for details.
 */
package net.caffeinemc.mods.sodium.api.util;

import org.jetbrains.annotations.ApiStatus;

/**
 * A collection of optimized color mixing functions which directly operate on packed color values. These functions are
 * agnostic to the ordering of color channels, and the output value will always use the same channel ordering as
 * the input values.
 */
public class ColorMixer {
    public static int mix(int start, int end, int weight) {
        final long hi = ((start & 0x00FF00FFL) * weight) + ((end & 0x00FF00FFL) * (ColorU8.COMPONENT_MASK - weight));
        final long lo = ((start & 0xFF00FF00L) * weight) + ((end & 0xFF00FF00L) * (ColorU8.COMPONENT_MASK - weight));

        final long result =
                (((hi + 0x00FF00FFL) >>> 8) & 0x00FF00FFL) |
                (((lo + 0xFF00FF00L) >>> 8) & 0xFF00FF00L);

        return (int) result;
    }

    public static int mix(int start, int end, float weight) {
        return mix(start, end, ColorU8.normalizedFloatToByte(weight));
    }

    @ApiStatus.Experimental
    public static int mix2d(int m00, int m01, int m10, int m11, float x, float y) {
        int x1 = ColorU8.normalizedFloatToByte(x), x0 = 255 - x1;
        int y1 = ColorU8.normalizedFloatToByte(y), y0 = 255 - y1;

        long row0a = ((((m00 & 0x00FF00FFL) * x0) + (((m10 & 0x00FF00FFL) * x1)) + 0x00FF00FFL) >>> 8) & 0x00FF00FFL;
        long row0b = ((((m00 & 0xFF00FF00L) * x0) + (((m10 & 0xFF00FF00L) * x1)) + 0xFF00FF00L) >>> 8) & 0xFF00FF00L;

        long row1a = ((((m01 & 0x00FF00FFL) * x0) + (((m11 & 0x00FF00FFL) * x1)) + 0x00FF00FFL) >>> 8) & 0x00FF00FFL;
        long row1b = ((((m01 & 0xFF00FF00L) * x0) + (((m11 & 0xFF00FF00L) * x1)) + 0xFF00FF00L) >>> 8) & 0xFF00FF00L;

        long result = ((((row0a * y0) + ((row1a * y1)) + 0x00FF00FFL) >>> 8) & 0x00FF00FFL) |
                      ((((row0b * y0) + ((row1b * y1)) + 0xFF00FF00L) >>> 8) & 0xFF00FF00L);

        return (int) result;
    }

    public static int mulComponentWise(int color0, int color1) {
        int comp0 = ((((color0 >>>  0) & 0xFF) * ((color1 >>>  0) & 0xFF)) + 0xFF) >>> 8;
        int comp1 = ((((color0 >>>  8) & 0xFF) * ((color1 >>>  8) & 0xFF)) + 0xFF) >>> 8;
        int comp2 = ((((color0 >>> 16) & 0xFF) * ((color1 >>> 16) & 0xFF)) + 0xFF) >>> 8;
        int comp3 = ((((color0 >>> 24) & 0xFF) * ((color1 >>> 24) & 0xFF)) + 0xFF) >>> 8;

        return (comp0 << 0) | (comp1 << 8) | (comp2 << 16) | (comp3 << 24);
    }

    public static int mul(int color, int factor) {
        final long hi = (color & 0x00FF00FFL) * factor;
        final long lo = (color & 0xFF00FF00L) * factor;

        final long result =
                (((hi + 0x00FF00FFL) >>> 8) & 0x00FF00FFL) |
                (((lo + 0xFF00FF00L) >>> 8) & 0xFF00FF00L);

        return (int) result;
    }

    public static int mul(int color, float factor) {
        return mul(color, ColorU8.normalizedFloatToByte(factor));
    }
}

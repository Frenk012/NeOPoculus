/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 *
 * These classes reproduce Sodium's stable public API (net.caffeinemc.mods.sodium.api.*)
 * so that mods written against Sodium can run on the Embeddium-based stack, where the
 * real Sodium classes are absent (Sodium and Embeddium are mutually exclusive).
 *
 * Source: CaffeineMC/Sodium, licensed under LGPL-3.0. NeOPoculus is LGPL-3.0-only,
 * so this inclusion is license-compatible. These utility classes are self-contained
 * (pure math / bit packing) and behave identically regardless of the rendering engine.
 */
package net.caffeinemc.mods.sodium.api.util;

public interface ColorU8 {
    int COMPONENT_BITS = 8;

    int COMPONENT_MASK = (1 << COMPONENT_BITS) - 1;

    float COMPONENT_RANGE = (float) COMPONENT_MASK;

    float COMPONENT_RANGE_INVERSE = 1.0f / COMPONENT_RANGE;

    static int normalizedFloatToByte(float value) {
        return (int) (value * COMPONENT_RANGE) & COMPONENT_MASK;
    }

    static float byteToNormalizedFloat(int value) {
        return (float) value * COMPONENT_RANGE_INVERSE;
    }
}

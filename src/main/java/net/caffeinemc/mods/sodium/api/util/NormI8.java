/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 * Source: CaffeineMC/Sodium (LGPL-3.0). See ColorU8 for details.
 */
package net.caffeinemc.mods.sodium.api.util;

import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Provides some utilities for working with packed normal vectors. Each normal component provides 8 bits of
 * precision in the range of [-1.0,1.0].
 */
public class NormI8 {
    private static final int X_COMPONENT_OFFSET = 0;
    private static final int Y_COMPONENT_OFFSET = 8;
    private static final int Z_COMPONENT_OFFSET = 16;

    private static final float COMPONENT_RANGE = 127.0f;

    private static final float NORM = 1.0f / COMPONENT_RANGE;

    public static int pack(Vector3fc normal) {
        return pack(normal.x(), normal.y(), normal.z());
    }

    public static int pack(float x, float y, float z) {
        int normX = encode(x);
        int normY = encode(y);
        int normZ = encode(z);

        return (normZ << Z_COMPONENT_OFFSET) | (normY << Y_COMPONENT_OFFSET) | (normX << X_COMPONENT_OFFSET);
    }

    private static int encode(float comp) {
        return ((int) (Mth.clamp(comp, -1.0F, 1.0F) * COMPONENT_RANGE) & 255);
    }

    public static float unpackX(int norm) {
        return ((byte) ((norm >> X_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }

    public static float unpackY(int norm) {
        return ((byte) ((norm >> Y_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }

    public static float unpackZ(int norm) {
        return ((byte) ((norm >> Z_COMPONENT_OFFSET) & 0xFF)) * NORM;
    }

    public static int flipPacked(int norm) {
        int normX = (((norm >> X_COMPONENT_OFFSET) & 0xFF) * -1) & 0xFF;
        int normY = (((norm >> Y_COMPONENT_OFFSET) & 0xFF) * -1) & 0xFF;
        int normZ = (((norm >> Z_COMPONENT_OFFSET) & 0xFF) * -1) & 0xFF;

        return (normZ << Z_COMPONENT_OFFSET) | (normY << Y_COMPONENT_OFFSET) | (normX << X_COMPONENT_OFFSET);
    }

    public static boolean isOpposite(int normA, int normB) {
        byte normAX = (byte) (normA >> X_COMPONENT_OFFSET);
        byte normAY = (byte) (normA >> Y_COMPONENT_OFFSET);
        byte normAZ = (byte) (normA >> Z_COMPONENT_OFFSET);

        byte normBX = (byte) (normB >> X_COMPONENT_OFFSET);
        byte normBY = (byte) (normB >> Y_COMPONENT_OFFSET);
        byte normBZ = (byte) (normB >> Z_COMPONENT_OFFSET);

        return normAX == -normBX && normAY == -normBY && normAZ == -normBZ;
    }

    public static Vector3f unpack(int packed, Vector3f output) {
        return output.set(unpackX(packed), unpackY(packed), unpackZ(packed));
    }
}

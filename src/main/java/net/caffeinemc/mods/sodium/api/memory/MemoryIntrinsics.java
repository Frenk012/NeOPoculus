/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 * Backed by LWJGL MemoryUtil instead of Sodium's Unsafe wrapper; behavior is identical.
 * See net.caffeinemc.mods.sodium.api.util.ColorU8 for licensing notes.
 */
package net.caffeinemc.mods.sodium.api.memory;

import org.lwjgl.system.MemoryUtil;

public class MemoryIntrinsics {
    public static void putFloat(long ptr, float value) {
        MemoryUtil.memPutFloat(ptr, value);
    }

    public static float getFloat(long ptr) {
        return MemoryUtil.memGetFloat(ptr);
    }

    public static void putInt(long ptr, int value) {
        MemoryUtil.memPutInt(ptr, value);
    }

    public static int getInt(long ptr) {
        return MemoryUtil.memGetInt(ptr);
    }

    public static void putShort(long ptr, short value) {
        MemoryUtil.memPutShort(ptr, value);
    }

    public static short getShort(long ptr) {
        return MemoryUtil.memGetShort(ptr);
    }

    public static void copyMemory(long src, long dst, int length) {
        MemoryUtil.memCopy(src, dst, length);
    }
}

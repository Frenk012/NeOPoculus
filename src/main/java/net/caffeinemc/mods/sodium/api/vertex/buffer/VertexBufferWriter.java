/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 * Source: CaffeineMC/Sodium (LGPL-3.0). jspecify @Nullable dropped (compile-only annotation).
 *
 * On the Embeddium-based stack no vertex consumer implements this interface, so tryOf()
 * always returns null and of() throws — exactly the contract Sodium guarantees when the
 * fast path is unavailable. Mods using tryOf() (e.g. Flerovium) then fall back to the
 * regular VertexConsumer path. A real fast path wired to Embeddium can be added later
 * without changing this surface.
 */
package net.caffeinemc.mods.sodium.api.vertex.buffer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import org.lwjgl.system.MemoryStack;

public interface VertexBufferWriter {
    static VertexBufferWriter of(VertexConsumer consumer) {
        if (consumer instanceof VertexBufferWriter writer && writer.canUseIntrinsics()) {
            return writer;
        }

        throw createUnsupportedVertexConsumerThrowable(consumer);
    }

    static VertexBufferWriter tryOf(VertexConsumer consumer) {
        if (consumer instanceof VertexBufferWriter writer && writer.canUseIntrinsics()) {
            return writer;
        }

        return null;
    }

    private static RuntimeException createUnsupportedVertexConsumerThrowable(VertexConsumer consumer) {
        var clazz = consumer.getClass();
        var name = clazz.getName();

        return new IllegalArgumentException(("The class %s does not implement interface VertexBufferWriter, " +
                "which is required for compatibility with Sodium (see: https://github.com/CaffeineMC/sodium/issues/1620)").formatted(name));
    }

    void push(MemoryStack stack, long ptr, int count, VertexFormat format);

    default boolean canUseIntrinsics() {
        return true;
    }

    static void copyInto(VertexBufferWriter writer,
                         MemoryStack stack, long ptr, int count,
                         VertexFormat format) {
        var length = count * format.getVertexSize();
        var copy = stack.nmalloc(length);

        MemoryIntrinsics.copyMemory(ptr, copy, length);

        writer.push(stack, copy, count, format);
    }
}

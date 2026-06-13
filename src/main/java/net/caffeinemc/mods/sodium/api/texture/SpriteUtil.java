/*
 * Sodium public API compatibility shim, bundled by NeOPoculus.
 *
 * Unlike real Sodium, INSTANCE is provided directly instead of through Sodium's
 * DependencyInjection loader (which would look for a Sodium-internal impl that does
 * not exist on the Embeddium stack and crash at class-init). markSpriteActive is a
 * no-op: on this stack sprite animations are driven by Embeddium/vanilla regardless,
 * and mods that would call it (e.g. Flerovium) only do so on the fast path, which is
 * inactive here. See net.caffeinemc.mods.sodium.api.util.ColorU8 for licensing notes.
 */
package net.caffeinemc.mods.sodium.api.texture;

import net.irisshaders.iris.mixin.texture.SpriteContentsAccessor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public interface SpriteUtil {
    SpriteUtil INSTANCE = new SpriteUtil() {
        @Override
        public void markSpriteActive(TextureAtlasSprite sprite) {
            // No-op: animations are ticked by the active rendering engine.
        }

        @Override
        public boolean hasAnimation(TextureAtlasSprite sprite) {
            try {
                return ((SpriteContentsAccessor) (Object) sprite.contents()).getAnimatedTexture() != null;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    void markSpriteActive(TextureAtlasSprite sprite);

    boolean hasAnimation(TextureAtlasSprite sprite);
}

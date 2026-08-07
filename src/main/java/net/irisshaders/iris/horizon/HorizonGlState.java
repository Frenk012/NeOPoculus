package net.irisshaders.iris.horizon;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import net.irisshaders.iris.mixin.statelisteners.BooleanStateAccessor;
import org.lwjgl.opengl.GL33C;

/**
 * State changes that both take effect and leave the cache honest.
 *
 * <p>A foreign render pass inside a shaderpack frame has two ways to go wrong,
 * and they are opposites:
 *
 * <ul>
 * <li>Raw {@code glDisable} alone changes the driver but not
 *     {@link GlStateManager}'s cache. Vanilla and Iris keep believing the old
 *     value, so their next call is skipped as redundant and our value survives
 *     into every later program the pack runs.</li>
 * <li>{@code GlStateManager._disableBlend()} alone is the mirror failure. It is
 *     cache-gated, and while a blend override is locked Iris's own mixin
 *     <em>cancels</em> it outright and merely records the intent. The driver
 *     never changes, so the pass draws under the pack's blend mode — for opaque
 *     LOD geometry that means blending away to nothing.</li>
 * </ul>
 *
 * <p>So each helper here does both halves: the raw call for the effect, then a
 * write into the cache so what Iris believes matches what the driver holds.
 * Only the states this pass actually forces are covered; anything that can go
 * through {@code GlStateManager} untouched should, because a plain call is
 * easier to reason about than a forced one.
 */
public final class HorizonGlState {
	private HorizonGlState() {
	}

	/**
	 * Turns blending on or off for real, past any active override lock, and
	 * records it so the pack's next toggle is not swallowed as a no-op.
	 */
	public static void forceBlend(boolean enabled) {
		if (enabled) {
			GL33C.glEnable(GL33C.GL_BLEND);
		} else {
			GL33C.glDisable(GL33C.GL_BLEND);
		}
		((BooleanStateAccessor) GlStateManagerAccessor.getBLEND().mode).setEnabled(enabled);
	}

	/** The texture unit index {@link GlStateManager} currently believes is active. */
	public static int activeUnit() {
		return GlStateManagerAccessor.getActiveTexture();
	}

	/** Makes {@code unit} active for real, and records it. */
	public static void forceActiveUnit(int unit) {
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
		GlStateManagerAccessor.setActiveTexture(unit);
	}

	/** The texture {@link GlStateManager} currently believes is bound to {@code unit}. */
	public static int boundTexture(int unit) {
		return GlStateManagerAccessor.getTEXTURES()[unit].binding;
	}

	/**
	 * Binds a 2D texture to a unit for real, and records it. Leaves {@code unit}
	 * active, which is what every caller here wants next.
	 */
	public static void forceBindTexture(int unit, int texture) {
		forceActiveUnit(unit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
		GlStateManagerAccessor.getTEXTURES()[unit].binding = texture;
	}
}

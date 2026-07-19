/**
 * Experimental textured voxel LOD engine ("engine=voxel" in
 * neoculus-horizon.properties), replacing the classic 2.5D color-extrusion
 * renderer in {@code net.irisshaders.iris.horizon} once at parity.
 *
 * <p>Design: docs/horizon-voxel/DESIGN.md (unified) plus the three slice
 * documents next to it. Techniques are inspired by the behavior of the Voxy
 * mod but reimplemented clean-room from a written description only; no Voxy
 * code or shaders were ported (Voxy is all-rights-reserved).
 *
 * <p>Platform floor: OpenGL 4.1 without compute shaders, SSBOs or MDI —
 * everything here must run on macOS and compute-less GPUs.
 */
package net.irisshaders.iris.horizon.voxel;

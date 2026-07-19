/**
 * Model bakery for the voxel LOD engine: software-rasterizes each unique
 * {@code BlockState}'s real baked model into six 16x16 per-face "photos"
 * with a linear-space mip chain, deduplicated into one shared 2D atlas.
 * Populated in milestone M4; see docs/horizon-voxel/design-bakery-atlas.md.
 */
package net.irisshaders.iris.horizon.voxel.model;

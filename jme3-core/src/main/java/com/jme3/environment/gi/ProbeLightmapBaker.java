package com.jme3.environment.gi;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.math.FastMath;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.List;

/**
 * Rasterizes the captured diffuse probe field onto a static mesh's existing UV2.
 * Requires a float-renderable OpenGL context. Call on the render thread between
 * render passes. Ownership of the returned texture and its storage passes to
 * the caller. RGB contains coverage-weighted irradiance. A bound static map
 * supplies the diffuse cache even where its probe coverage is incomplete.
 * Built-in PBR normal maps use UV0, tangents and the material's normal scale
 * and convention. The bake captures a fixed normal without view-based parallax.
 * Targets up to 512 pixels per axis use four subtexel samples when the
 * renderer supports the larger temporary raster. Resolve averages only
 * covered samples before gutter fill. The saved dimensions are unchanged.
 * For ordinary float UV2 triangle meshes, gutter samples stay within the
 * nearest connected UV island. An island without a nearby covered sample
 * keeps the texel empty.
 * On maps up to 256 pixels per axis, a finer temporary raster can recover
 * missed chart-edge texels. It only fills empty texels from their own island.
 * By default, paired smooth geometric edges blend irradiance across a narrow
 * UV2 seam band. Coverage and baked sky remain specific to each island.
 * The baker stores explicit mip levels while the mesh's UV2 and LOD index
 * buffers leave enough room for chart-isolated bilinear filtering. Tight
 * layouts may produce only the base level. The returned texture initially
 * uses base-level filtering. Always check for a stored mip chain before
 * changing its filter; ordinary generated mipmaps mix neighboring charts.
 * Leave room between UV charts at the bake resolution. The bake extends the
 * nearest rasterized texel up to two texels into empty space for bilinear
 * filtering. Existing lightmaps need to be baked again to get the border.
 * <pre>{@code
 * // 第一步：完成 LightProbe Volume 烘焙，准备非重叠 UV2，并给 UV 岛留边。
 * // 保留原材质、UV0 和切线；修改法线贴图后重新生成 Lightmap。
 * // 若原 UV2 无重叠且留边足够，可复用 UV2 来重烘焙现有贴图。
 * // 烘焙器会在输出中缝合平滑边；旧 Lightmap 需要重新生成才会生效。
 * Texture2D lightmap = ProbeLightmapBaker.bake(renderManager, assetManager,
 *         geometry, volume.getData(), volume.getWorldTranslation(), 256, 256);
 * // 可直接检查已烘焙的 mip；旧资产没有 mip，保持原始采样。
 * boolean hasMipmaps = lightmap.getImage().hasMipmaps();
 * // 第二步：将纹理另存为 GI/Lightmaps 下的独立 J3O 文件。
 * BinaryExporter.getInstance().save(lightmap, lightmapFile);
 * // 第三步：在场景上保存 ProbeLightmapControl 资产引用；移除控制器可恢复探针。
 * }</pre>
 */
public final class ProbeLightmapBaker {
    private static final int GUTTER_RADIUS = 2;
    private static final int FLOAT_PIXEL_BYTES = 4 * Float.BYTES;
    private ProbeLightmapBaker() {
    }

    public static Texture2D bake(RenderManager manager, AssetManager assets, Geometry geometry,
            GiProbeData data, Vector3f offset, int width, int height) {
        if (data.hasSeparatedSky()) {
            throw new IllegalArgumentException("Separated sky requires a probe field lightmap");
        }
        return rasterize(manager, assets, geometry, width, height, false, true,
                material -> data.bind(material, offset, .05f));
    }

    /**
     * Creates a reusable field for baking the same overlap policy and sky payload as live probes.
     * Open and close it on the render thread, between scene passes. If octahedral pages do not
     * fit, try SH using the same visibility policy. The caller may continue with live probes if
     * neither field fits. Source entries must cover all volumes which can contribute at a receiver.
     * <pre>{@code
     * // 第一步：将当前区域所有能参与混合的 Volume 放入 entries。
     * try (GiProbeSet field = ProbeLightmapBaker.openField(renderManager, entries, budget, true, false)) {
     *     // 第二步：使用 UV2 烘焙，将返回的图集保存为独立资产。
     *     Texture2D lightmap = ProbeLightmapBaker.bakeField(renderManager, assetManager, geometry, field, 256, 256);
     * }
     * // 第三步：用 ProbeLightmapAsset 标记图集，再用 ProbeLightmapControl 引用资产路径。
     * }</pre>
     */
    public static GiProbeSet openField(RenderManager manager, List<GiProbeSet.Entry> entries, long maxBytes,
            boolean singleVisibility, boolean octahedral) {
        int limit = manager.getRenderer().getLimits().getOrDefault(Limits.TextureSize, 0);
        if (octahedral) {
            try {
                return new GiProbeSet(entries, limit, maxBytes, singleVisibility, true);
            } catch (IllegalArgumentException tooLarge) {
                // Match the runtime SH fallback when directional texels do not fit.
            }
        }
        return new GiProbeSet(entries, limit, maxBytes, singleVisibility, false);
    }

    /** Bakes a two-band texture with UV2 seam blending enabled. */
    public static Texture2D bakeField(RenderManager manager, AssetManager assets, Geometry geometry,
            GiProbeSet field, int width, int height) {
        return bakeField(manager, assets, geometry, field, width, height, true);
    }

    /**
     * Bakes diffuse irradiance and independent sky visibility into one two-band texture.
     * The seam option controls only the final blend between paired UV2 edges. Coverage
     * resolve and chart-aware gutters still run. Call on the render thread with an open
     * probe field. To change an existing map, bake again and replace its asset reference.
     * <pre>{@code
     * // 第一步：打开探针场，准备 UV2 并确定 Lightmap 尺寸。
     * // 第二步：传入烘焙选项；默认传 true，关闭时可对照接缝差异。
     * Texture2D lightmap = ProbeLightmapBaker.bakeField(renderManager, assetManager,
     *         geometry, field, 512, 512, blendSeams);
     * // 第三步：保存控制器引用的新贴图；恢复平滑时用 true 再烘焙。
     * }</pre>
     *
     * @param manager the render manager for UV2 capture
     * @param assets the asset manager that loads the bake material
     * @param geometry the static lightmap receiver
     * @param field the open probe field supplying irradiance and sky visibility
     * @param width lightmap width in texels
     * @param height height of each band in texels
     * @param blendSeams whether to blend irradiance along matched smooth UV2 edges
     * @return the two-band texture, owned by the caller
     */
    public static Texture2D bakeField(RenderManager manager, AssetManager assets, Geometry geometry,
            GiProbeSet field, int width, int height, boolean blendSeams) {
        return rasterize(manager, assets, geometry, width, height, true, blendSeams,
                material -> field.bind(material, .05f));
    }

    /**
     * Bakes one directional light's color and static receiver visibility
     * from the shared shadow snapshot. Playback applies the live pixel normal.
     * The output alpha
     * is one on covered texels and zero elsewhere. See {@link BakedDirectionalShadow}
     * for the full workflow. The caller owns the returned texture.
     */
    public static Texture2D bakeDirect(RenderManager manager, AssetManager assets, Geometry geometry,
            BakedDirectionalShadow shadow, int width, int height) {
        return bakeDirect(manager, assets, geometry, shadow, width, height, 0, 3);
    }

    /**
     * Bakes a fixed-width PCF shadow into a direct Lightmap. Radius is measured
     * in shadow-map texels. Zero restores the original bilinear comparison.
     * Samples per axis (2..8) control the sampling density at the chosen radius
     * (0..8), independently of the UV2 resolution. Call on the render thread.
     * <pre>{@code
     * // 第一步：捕获静态方向光深度，可复用同一快照比较过滤参数。
     * BakedDirectionalShadow depth = BakedDirectionalShadow.capture(manager, scene, light, 2048);
     * // 第二步：半径 2 个阴影纹素，每轴 5 个采样，烘焙到该物体的 UV2。
     * Texture2D band = ProbeLightmapBaker.bakeDirect(manager, assets, receiver, depth, 512, 512, 2, 5);
     * // 第三步：用 ProbeLightmapAsset.withDirect 保存直射层；恢复旧效果时以半径 0 重烘焙。
     * }</pre>
     * @return the direct band, owned by the caller
     */
    public static Texture2D bakeDirect(RenderManager manager, AssetManager assets, Geometry geometry,
            BakedDirectionalShadow shadow, int width, int height, int radius, int samplesPerAxis) {
        if (radius < 0 || radius > 8 || samplesPerAxis < 2 || samplesPerAxis > 8) {
            throw new IllegalArgumentException("Direct shadow filter requires radius 0..8 and samples per axis 2..8");
        }
        return rasterize(manager, assets, geometry, width, height, false, false, material -> {
            shadow.bindBake(material);
            material.setInt("BakedShadowRadius", radius);
            material.setInt("BakedShadowSamples", samplesPerAxis);
            return () -> { };
        });
    }

    private static Texture2D rasterize(RenderManager manager, AssetManager assets, Geometry geometry,
            int width, int height, boolean atlas, boolean blendSeams,
            java.util.function.Function<Material, AutoCloseable> binder) {
        if (geometry.getMobility() != Mobility.Static || geometry.getMesh().isAnimated()
                || geometry.getMesh().getMorphTargets().length != 0 || geometry.getMesh().getBuffer(Type.Normal) == null
                || geometry.getMesh().getBuffer(Type.TexCoord2) == null
                || !manager.getRenderer().getCaps().contains(Caps.FloatColorBufferRGBA)
                || width < 2 || height < 2 || width > 2048 || height > 2048) {
            throw new IllegalArgumentException("Static lightmap requires UV2 and float targets");
        }
        var renderer = manager.getRenderer();
        int outputHeight = atlas ? 2 * height : height;
        int textureLimit = renderer.getLimits().getOrDefault(Limits.TextureSize, 0);
        if (width > textureLimit || outputHeight > textureLimit) {
            throw new IllegalArgumentException("Lightmap exceeds maximum texture size");
        }
        // Subtexel coverage recovers narrow UV triangles missed by the center
        // sample. Cap the temporary target to bound the readback cost.
        int samplesPerAxis = width <= 512 && height <= 512
                && width * 2 <= textureLimit && outputHeight * 2 <= textureLimit ? 2 : 1;
        int rasterWidth = width * samplesPerAxis;
        int rasterHeight = outputHeight * samplesPerAxis;
        var oldTarget = renderer.getCurrentFrameBuffer();
        var previousCamera = manager.getCurrentCamera();
        var target = new FrameBuffer(rasterWidth, rasterHeight, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        var proxy = new Geometry("Probe Lightmap UV2", geometry.getMesh());
        proxy.setLocalTransform(geometry.getWorldTransform().clone());
        proxy.setIgnoreTransform(geometry.isIgnoreTransform());
        Material bakeMaterial = new Material(assets, "Common/MatDefs/Light/ProbeLightmapBake.j3md");
        bindPbrNormalMap(geometry, bakeMaterial);
        proxy.setMaterial(bakeMaterial);
        proxy.updateGeometricState();
        ByteBuffer readback = BufferUtils.createByteBuffer(rasterWidth * rasterHeight * FLOAT_PIXEL_BYTES);
        ByteBuffer resolved = samplesPerAxis == 1 ? readback
                : BufferUtils.createByteBuffer(width * outputHeight * FLOAT_PIXEL_BYTES);
        try (AutoCloseable binding = binder.apply(proxy.getMaterial())) {
            capture(manager, renderer, proxy, target, readback, atlas);
            if (samplesPerAxis > 1) {
                resolveSubtexels(readback, resolved, width, height, atlas, samplesPerAxis, null, null, null);
            }
            boolean[] boundary = width <= 256 && height <= 256
                    && width * 4 <= textureLimit && outputHeight * 4 <= textureLimit ? new boolean[width * height] : null;
            var charts = ProbeLightmapChartOwnership.charts(geometry.getMesh());
            int[] owners = ProbeLightmapChartOwnership.rasterize(charts, width, height, GUTTER_RADIUS, boundary);
            if (owners != null && boundary != null && markMissing(resolved, boundary)) {
                recoverMissingSamples(manager, renderer, proxy, target, resolved, width, height, atlas, owners, boundary);
            }
            ByteBuffer half = BufferUtils.createByteBuffer(width * outputHeight * 8);
            BitSet filled = new BitSet();
            fillLightmapGutters(resolved, half, width, height, atlas, owners, filled);
            if (blendSeams) {
                ProbeLightmapSeamBlend.blend(geometry.getMesh(), half, width, height, owners);
            }
            half.flip();
            Image lightmap = bakeMaterial.getParam("BakedShadowDepth") == null
                    ? ProbeLightmapMipChain.build(geometry.getMesh(), charts, half, owners, filled, width, height, atlas)
                    : new Image(Image.Format.RGBA16F, width, height, half, ColorSpace.Linear);
            if (lightmap.hasMipmaps()) {
                BufferUtils.destroyDirectBuffer(half);
            }
            Texture2D texture = new Texture2D(lightmap);
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
            if (lightmap.hasMipmaps()) {
                // Chart separation was validated for a bilinear footprint.
                texture.setAnisotropicFilter(1);
            }
            return texture;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not release the probe lightmap field", failure);
        } finally {
            renderer.setFrameBuffer(oldTarget);
            if (previousCamera != null) {
                manager.setCamera(previousCamera, false);
            }
            renderer.deleteFrameBuffer(target);
            if (resolved != readback) {
                BufferUtils.destroyDirectBuffer(resolved);
            }
            BufferUtils.destroyDirectBuffer(readback);
        }
    }

    private static void capture(RenderManager manager, Renderer renderer,
            Geometry proxy, FrameBuffer target, ByteBuffer pixels, boolean atlas) {
        renderer.setFrameBuffer(target);
        manager.setCamera(new Camera(target.getWidth(), target.getHeight()), false);
        renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
        renderer.clearBuffers(true, false, false);
        proxy.getMaterial().setInt("LightmapBand", atlas ? 1 : 0);
        manager.renderGeometry(proxy);
        if (atlas) {
            proxy.getMaterial().setInt("LightmapBand", 2);
            manager.renderGeometry(proxy);
        }
        renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
    }

    static boolean markMissing(ByteBuffer resolved, boolean[] boundary) {
        boolean any = false;
        for (int i = 0; i < boundary.length; i++) {
            boundary[i] &= !isRasterized(resolved, i);
            any |= boundary[i];
        }
        return any;
    }

    private static void recoverMissingSamples(RenderManager manager, Renderer renderer, Geometry proxy,
            FrameBuffer originalTarget, ByteBuffer resolved, int width, int height, boolean atlas,
            int[] owners, boolean[] missing) {
        int fineWidth = width * 4;
        int fineHeight = height * 4;
        int[] fineOwners = ProbeLightmapChartOwnership.rasterize(proxy.getMesh(), fineWidth, fineHeight, 0);
        if (fineOwners == null) {
            return;
        }
        var target = new FrameBuffer(fineWidth, atlas ? fineHeight * 2 : fineHeight, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        ByteBuffer raster = BufferUtils.createByteBuffer(fineWidth * target.getHeight() * FLOAT_PIXEL_BYTES);
        try {
            capture(manager, renderer, proxy, target, raster, atlas);
            resolveSubtexels(raster, resolved, width, height, atlas, 4, missing, owners, fineOwners);
        } finally {
            renderer.setFrameBuffer(originalTarget);
            renderer.deleteFrameBuffer(target);
            BufferUtils.destroyDirectBuffer(raster);
        }
    }

    static void resolveSubtexels(ByteBuffer raster, ByteBuffer resolved,
            int width, int height, boolean atlas, int samplesPerAxis,
            boolean[] missing, int[] owners, int[] fineOwners) {
        int rasterWidth = width * samplesPerAxis;
        int bandOffset = rasterWidth * height * samplesPerAxis;
        int outputBand = width * height;
        float[] sums = new float[atlas ? 8 : 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                if (missing != null && !missing[pixel]) {
                    continue;
                }
                java.util.Arrays.fill(sums, 0f);
                int count = 0;
                for (int dy = 0; dy < samplesPerAxis; dy++) {
                    for (int dx = 0; dx < samplesPerAxis; dx++) {
                        int source = (y * samplesPerAxis + dy) * rasterWidth + x * samplesPerAxis + dx;
                        if (!isRasterized(raster, source)
                                || fineOwners != null && fineOwners[source] != owners[pixel]) {
                            continue;
                        }
                        count++;
                        for (int channel = 0; channel < 4; channel++) {
                            int offset = source * FLOAT_PIXEL_BYTES + channel * Float.BYTES;
                            sums[channel] += raster.getFloat(offset);
                            if (atlas) {
                                sums[4 + channel] += raster.getFloat(bandOffset * FLOAT_PIXEL_BYTES + offset);
                            }
                        }
                    }
                }
                int output = (y * width + x) * FLOAT_PIXEL_BYTES;
                float weight = count == 0 ? 0f : 1f / count;
                for (int channel = 0; channel < sums.length; channel++) {
                    int offset = channel < 4 ? channel * Float.BYTES
                            : outputBand * FLOAT_PIXEL_BYTES + (channel - 4) * Float.BYTES;
                    resolved.putFloat(output + offset, sums[channel] * weight);
                }
            }
        }
    }

    private static void bindPbrNormalMap(Geometry geometry, Material bakeMaterial) {
        Material source = geometry.getMaterial();
        if (source == null || !"Common/MatDefs/Light/PBRLighting.j3md".equals(source.getMaterialDef().getAssetName())
                || geometry.getMesh().getBuffer(Type.Tangent) == null
                || geometry.getMesh().getBuffer(Type.TexCoord) == null) {
            return;
        }
        Object map = surfaceParameter(geometry, "NormalMap");
        if (!(map instanceof Texture2D)) {
            return;
        }
        bakeMaterial.setTexture("NormalMap", (Texture2D) map);
        Object type = surfaceParameter(geometry, "NormalType");
        Object scale = surfaceParameter(geometry, "NormalScale");
        bakeMaterial.setFloat("NormalType", type instanceof Number ? ((Number) type).floatValue() : -1f);
        bakeMaterial.setFloat("NormalScale", scale instanceof Number ? ((Number) scale).floatValue() : 1f);
    }

    private static Object surfaceParameter(Geometry geometry, String name) {
        Material material = geometry.getMaterial();
        Object value = material.getParamValue(name);
        for (var override : geometry.getWorldMatParamOverrides()) {
            if (override.isEnabled() && override.getName().equals(name)
                    && material.getMaterialDef().getMaterialParam(name).getVarType() == override.getVarType()) {
                value = override.getValue();
            }
        }
        return value;
    }

    private static void fillLightmapGutters(ByteBuffer raster, ByteBuffer half,
            int width, int height, boolean atlas, int[] owners, BitSet filled) {
        int bandPixels = width * height;
        int[] sources = atlas ? new int[bandPixels] : null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                int source = pixel;
                if (!isRasterized(raster, pixel)) {
                    source = nearestRasterized(raster, width, height, x, y, owners);
                }
                if (atlas) {
                    sources[pixel] = source;
                }
                if (owners != null && source >= 0) {
                    filled.set(pixel);
                }
                writeTexel(raster, half, source, true);
            }
        }
        if (atlas) {
            // Both bands follow the same source to keep bent sky and irradiance aligned.
            for (int source : sources) {
                writeTexel(raster, half, source < 0 ? -1 : bandPixels + source, false);
            }
        }
    }

    private static boolean isRasterized(ByteBuffer raster, int pixel) {
        return raster.getFloat(pixel * FLOAT_PIXEL_BYTES + 3 * Float.BYTES) >= 1f;
    }

    static int nearestRasterized(ByteBuffer raster, int width, int height, int x, int y, int[] owners) {
        int closest = -1;
        int bestDistance = 2 * GUTTER_RADIUS * GUTTER_RADIUS + 1;
        int chart = owners == null ? -1 : owners[y * width + x];
        for (int dy = Math.max(-GUTTER_RADIUS, -y); dy <= Math.min(GUTTER_RADIUS, height - 1 - y); dy++) {
            int row = (y + dy) * width;
            for (int dx = Math.max(-GUTTER_RADIUS, -x); dx <= Math.min(GUTTER_RADIUS, width - 1 - x); dx++) {
                int distance = dx * dx + dy * dy;
                int candidate = row + x + dx;
                if (distance < bestDistance && (owners == null || chart >= 0 && chart == owners[candidate])
                        && isRasterized(raster, candidate)) {
                    closest = candidate;
                    bestDistance = distance;
                }
            }
        }
        return closest;
    }

    private static void writeTexel(ByteBuffer raster, ByteBuffer half, int pixel, boolean irradiance) {
        if (pixel < 0) {
            half.putLong(0L);
            return;
        }
        int offset = pixel * FLOAT_PIXEL_BYTES;
        for (int channel = 0; channel < 3; channel++) {
            half.putShort(FastMath.convertFloatToHalf(raster.getFloat(offset + channel * Float.BYTES)));
        }
        float alpha = raster.getFloat(offset + 3 * Float.BYTES);
        // Remove the temporary raster occupancy marker from the stored coverage.
        half.putShort(FastMath.convertFloatToHalf(irradiance ? alpha - 1f : alpha));
    }
}

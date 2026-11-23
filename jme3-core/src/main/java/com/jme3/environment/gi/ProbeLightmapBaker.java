package com.jme3.environment.gi;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
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
import java.util.List;

/**
 * Rasterizes the captured diffuse probe field onto a static mesh's existing UV2.
 * Requires a float-renderable OpenGL context. Call on the render thread between
 * render passes. Ownership of the returned texture and its storage passes to
 * the caller. Its alpha encodes valid coverage; missing texels retain live GI.
 * <pre>{@code
 * // 第一步：完成 LightProbe Volume 的完整间接光烘焙，准备非重叠 UV2。
 * Texture2D lightmap = ProbeLightmapBaker.bake(renderManager, assetManager,
 *         geometry, volume.getData(), volume.getWorldTranslation(), 256, 256);
 * // 第二步：将纹理另存为 GI/Lightmaps 下的独立 J3O 文件。
 * BinaryExporter.getInstance().save(lightmap, lightmapFile);
 * // 第三步：在场景上保存 ProbeLightmapControl 资产引用；移除控制器可恢复探针。
 * }</pre>
 */
public final class ProbeLightmapBaker {
    private ProbeLightmapBaker() {
    }

    public static Texture2D bake(RenderManager manager, AssetManager assets, Geometry geometry,
            GiProbeData data, Vector3f offset, int width, int height) {
        if (data.hasSeparatedSky()) {
            throw new IllegalArgumentException("Separated sky requires a probe field lightmap");
        }
        return rasterize(manager, assets, geometry, width, height, false,
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

    /** Bakes diffuse irradiance and independent sky visibility into one two-band texture. */
    public static Texture2D bakeField(RenderManager manager, AssetManager assets, Geometry geometry,
            GiProbeSet field, int width, int height) {
        return rasterize(manager, assets, geometry, width, height, true, material -> field.bind(material, .05f));
    }

    private static Texture2D rasterize(RenderManager manager, AssetManager assets, Geometry geometry,
            int width, int height, boolean atlas, java.util.function.Function<Material, AutoCloseable> binder) {
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
        var oldTarget = renderer.getCurrentFrameBuffer();
        var previousCamera = manager.getCurrentCamera();
        var target = new FrameBuffer(width, outputHeight, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        var proxy = new Geometry("Probe Lightmap UV2", geometry.getMesh());
        proxy.setLocalTransform(geometry.getWorldTransform().clone());
        proxy.setIgnoreTransform(geometry.isIgnoreTransform());
        proxy.setMaterial(new Material(assets, "Common/MatDefs/Light/ProbeLightmapBake.j3md"));
        proxy.updateGeometricState();
        ByteBuffer readback = BufferUtils.createByteBuffer(width * outputHeight * 16);
        try (AutoCloseable binding = binder.apply(proxy.getMaterial())) {
            renderer.setFrameBuffer(target);
            manager.setCamera(new Camera(width, outputHeight), false);
            renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
            renderer.clearBuffers(true, false, false);
            proxy.getMaterial().setInt("LightmapBand", atlas ? 1 : 0);
            manager.renderGeometry(proxy);
            if (atlas) {
                proxy.getMaterial().setInt("LightmapBand", 2);
                manager.renderGeometry(proxy);
            }
            renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
            ByteBuffer half = BufferUtils.createByteBuffer(width * outputHeight * 8);
            for (int i = 0; i < width * outputHeight * 4; i++) {
                half.putShort(FastMath.convertFloatToHalf(readback.getFloat(i * 4)));
            }
            half.flip();
            Texture2D texture = new Texture2D(new Image(Image.Format.RGBA16F, width, outputHeight, half, ColorSpace.Linear));
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
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
            BufferUtils.destroyDirectBuffer(readback);
        }
    }
}

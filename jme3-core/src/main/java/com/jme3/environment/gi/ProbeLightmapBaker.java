package com.jme3.environment.gi;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
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
        if (geometry.getMobility() != Mobility.Static || geometry.getMesh().isAnimated()
                || geometry.getMesh().getMorphTargets().length != 0 || geometry.getMesh().getBuffer(Type.Normal) == null
                || geometry.getMesh().getBuffer(Type.TexCoord2) == null || data.hasSeparatedSky()
                || !manager.getRenderer().getCaps().contains(Caps.FloatColorBufferRGBA)
                || width < 2 || height < 2 || width > 2048 || height > 2048) {
            throw new IllegalArgumentException("Static lightmap requires total irradiance, UV2, and float targets");
        }
        var renderer = manager.getRenderer();
        var oldTarget = renderer.getCurrentFrameBuffer();
        var previousCamera = manager.getCurrentCamera();
        var target = new FrameBuffer(width, height, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        var proxy = new Geometry("Probe Lightmap UV2", geometry.getMesh());
        proxy.setLocalTransform(geometry.getWorldTransform().clone());
        proxy.setIgnoreTransform(geometry.isIgnoreTransform());
        proxy.setMaterial(new Material(assets, "Common/MatDefs/Light/ProbeLightmapBake.j3md"));
        proxy.updateGeometricState();
        ByteBuffer readback = BufferUtils.createByteBuffer(width * height * 16);
        try (GiProbeData.Binding binding = data.bind(proxy.getMaterial(), offset, .05f)) {
            renderer.setFrameBuffer(target);
            manager.setCamera(new Camera(width, height), false);
            renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
            renderer.clearBuffers(true, false, false);
            manager.renderGeometry(proxy);
            renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
            ByteBuffer half = BufferUtils.createByteBuffer(width * height * 8);
            for (int i = 0; i < width * height * 4; i++) {
                half.putShort(FastMath.convertFloatToHalf(readback.getFloat(i * 4)));
            }
            half.flip();
            Texture2D texture = new Texture2D(new Image(Image.Format.RGBA16F, width, height, half, ColorSpace.Linear));
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
            return texture;
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

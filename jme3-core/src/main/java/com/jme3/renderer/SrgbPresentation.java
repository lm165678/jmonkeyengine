/*
 * Copyright (c) 2009-2025 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.ui.Picture;

/**
 * Owns a linear floating-point composition target and an encoded SDR readback
 * target. No tone mapping is performed: the caller must tone map HDR scene
 * radiance before presentation. UI and transparency must be composited before
 * encoding. Neither target enables hardware sRGB writes. Alpha is unchanged.
 * All methods, including close, belong to the owning render thread.
 * Unsupported floating-point targets fail explicitly; callers may keep their
 * existing output path instead. Recreate after resize; close after detaching
 * viewports/processors that reference the composition target.
 * <pre>{@code
 * // 第一步：保持颜色纹理的 sRGB 解码，创建引擎拥有的线性合成目标。
 * SrgbPresentation output = new SrgbPresentation(assetManager, renderer, width, height);
 * viewPort.setOutputFrameBuffer(output.getCompositionTarget());
 * // 第二步：在此目标前完成浮点 HDR 后处理/Tonemap，UI 在线性显示空间合成。
 * // 第三步：所有视口完成后编码一次；不要再向编码结果混合线性颜色。
 * FrameBuffer encoded = output.encode(renderManager);
 * renderer.readFrameBuffer(encoded, pixels);
 * // 第四步：恢复原视口目标并重建关联 FPP 后，关闭 output 恢复旧输出路径。
 * }</pre>
 */
public final class SrgbPresentation implements AutoCloseable {
    private final FrameBuffer composition;
    private final FrameBuffer encoded;
    private final Texture2D color;
    private final Picture quad;
    private final Camera camera;

    public SrgbPresentation(AssetManager assets, Renderer renderer, int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Presentation dimensions must be positive");
        }
        Image.Format format;
        if (renderer.getCaps().contains(Caps.HalfFloatColorBufferRGBA)) {
            format = Image.Format.RGBA16F;
        } else if (renderer.getCaps().contains(Caps.FloatColorBufferRGBA)) {
            format = Image.Format.RGBA32F;
        } else {
            throw new UnsupportedOperationException("Floating-point presentation requires float color targets");
        }
        color = new Texture2D(width, height, format);
        color.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        color.setMagFilter(Texture.MagFilter.Nearest);
        composition = new FrameBuffer(width, height, 1);
        composition.setName("Presentation/LinearComposition");
        composition.setSrgb(false);
        composition.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(color));
        composition.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        encoded = new FrameBuffer(width, height, 1);
        encoded.setName("Presentation/EncodedSDR");
        encoded.setSrgb(false);
        encoded.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
        Material material = new Material(assets, "Common/MatDefs/Blit/Blit.j3md");
        material.setTexture("Texture", color);
        material.setBoolean("Srgb", true);
        material.getAdditionalRenderState().setDepthTest(false);
        material.getAdditionalRenderState().setDepthWrite(false);
        quad = new Picture("Presentation/EncodeSRGB");
        quad.setWidth(1);
        quad.setHeight(1);
        quad.setMaterial(material);
        quad.updateGeometricState();
        camera = new Camera(width, height);
    }

    /** Linear composition target; owns its depth and color attachments. */
    public FrameBuffer getCompositionTarget() {
        return composition;
    }

    /** Encodes RGB with the piecewise sRGB transfer function, preserving alpha. */
    public FrameBuffer encode(RenderManager manager) {
        present(manager, encoded);
        return encoded;
    }

    /**
     * Encodes directly into a non-sRGB target, or the current backend's main
     * framebuffer when null. Main-framebuffer hardware sRGB writes are disabled
     * for this draw and the previous policy is restored afterward. Call after
     * composition, before swap/present; do not draw linear overlays afterward.
     * A backend main-framebuffer override must refer to the intended output,
     * never to this object's composition target. Only backend owners with no
     * later encoding stage may use null. In particular, do not call this into
     * LWJGL's automatic linear blit target: its final shader already encodes.
     * A backend with raw main output may replace encode/readback with:
     * <pre>{@code
     * // 最后一步：全部合成后直接全屏编码到窗口，不需要额外复制。
     * output.present(renderManager, null);
     * }</pre>
     * @param manager owning render manager
     * @param target non-sRGB output target, or null for backend main output
     */
    public void present(RenderManager manager, FrameBuffer target) {
        if (target == composition || (target != null && target.isSrgb())) {
            throw new IllegalArgumentException("Output must be a distinct non-sRGB target");
        }
        Renderer renderer = manager.getRenderer();
        FrameBuffer previous = renderer.getCurrentFrameBuffer();
        Camera previousCamera = manager.getCurrentCamera();
        boolean previousSrgb = renderer.isMainFrameBufferSrgb();
        Material previousMaterial = manager.getForcedMaterial();
        String previousTechnique = manager.getForcedTechnique();
        com.jme3.material.RenderState previousState = manager.getForcedRenderState();
        try {
            if (target == null) {
                renderer.setMainFrameBufferSrgb(false);
            }
            renderer.setFrameBuffer(target);
            if (target != null) {
                camera.resize(target.getWidth(), target.getHeight(), false);
            }
            manager.setCamera(camera, true);
            manager.setForcedMaterial(null);
            manager.setForcedTechnique(null);
            manager.setForcedRenderState(null);
            manager.renderGeometry(quad);
        } finally {
            manager.setForcedMaterial(previousMaterial);
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedRenderState(previousState);
            renderer.setMainFrameBufferSrgb(previousSrgb);
            renderer.setFrameBuffer(previous);
            if (previousCamera != null) {
                manager.setCamera(previousCamera, previousCamera.isParallelProjection());
            }
        }
    }

    @Override
    public void close() {
        composition.dispose();
        encoded.dispose();
        color.getImage().dispose();
    }
}

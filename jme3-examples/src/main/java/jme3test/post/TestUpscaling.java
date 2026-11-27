/*
 * Copyright (c) 2009-2026 jMonkeyEngine
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
package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.Filter;
import com.jme3.post.filters.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Native FSR1/FSR2, render-scale, AA restoration and native-size GUI regression.
 * Requires LWJGL3 OpenGL4.3 and float render targets. Run --verify or Gradle
 * upscalingSmoke. Checks all four render paths; success prints UPSCALING_GPU_PASS.
 * Includes both FSR2 opaque velocity modes, moving geometry/current-minus-previous
 * motion, disocclusion and alpha
 * preservation, plus a static edge ROI warmed for 64 frames and sampled for 32
 * frames (per-channel range <= .25). These are synthetic regressions, not a
 * production moving-scene quality guarantee or performance benchmark.
 * Includes native-size, jitter-free offline capture suspension and restoration.
 * <pre>{@code
 * // 第一步：单采样浮点 FPP，保留原来的后处理配置。
 * fpp.setFrameBufferFormat(Image.Format.RGBA16F);
 * fpp.setNumSamples(1);
 * // 第二步：添加 UpscalingPass，并选择算法和内部渲染比例。
 * upscale.setAlgorithm(UpscalingPass.Algorithm.FSR2);
 * upscale.setRenderScale(.67f);
 * fpp.addFilter(upscale);
 * // 第三步：在渲染线程切换独立速度 Pass；恢复默认主绘制用 true，切换会重置历史。
 * upscale.setBasePassOutputsVelocity(false);
 * upscale.setBasePassOutputsVelocity(true);
 * // GUI 继续挂到独立 guiViewPort，不放入低分辨率场景。
 * // 第四步：upscale.setEnabled(false) 恢复原生尺寸及原抗锯齿；相机切换需 resetHistory。
 * // 第五步：烘焙前对超分、TAA 取得临时句柄，等待一个正常帧再采样。
 * Filter.CaptureSuspension scope = upscale.suspendForCapture();
 * // 完成、取消或异常后在渲染线程 close；用户启用偏好仍能正常保存到 J3O。
 * scope.close();
 * }</pre>
 */
public class TestUpscaling extends SimpleApplication {
    private static final int SEPARATE_VELOCITY_STAGE = 7;
    private final RenderPath[] paths = {RenderPath.Forward, RenderPath.Deferred,
            RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private FilterPostProcessor fpp;
    private UpscalingPass upscale = new UpscalingPass();
    private TemporalAAFilter taa = new TemporalAAFilter();
    private FXAAFilter fxaa = new FXAAFilter();
    private FrameBuffer output;
    private Geometry moving;
    private final float[] minimum = new float[80 * 90 * 3];
    private final float[] maximum = new float[80 * 90 * 3];
    private int path, stage, frame;
    private Filter.CaptureSuspension upscaleCapture;
    private Filter.CaptureSuspension taaCapture;

    public static void main(String[] args) {
        TestUpscaling app = new TestUpscaling();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(256, 192);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    private Geometry quad(String name, float width, float height, ColorRGBA color) {
        Geometry geometry = new Geometry(name, new Quad(width, height));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        geometry.setMaterial(material);
        return geometry;
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setEnabled(false);
        cam.setLocation(new Vector3f(0, 0, 4));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        output = new FrameBuffer(256, 192, 1);
        output.setSrgb(false);
        output.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
        output.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        viewPort.setOutputFrameBuffer(output);
        guiViewPort.setOutputFrameBuffer(output);
        Geometry scene = quad("Scene", 5, 5, new ColorRGBA(.18f, .2f, .4f, .4f));
        scene.setLocalTranslation(-2.5f, -2.5f, 0);
        rootNode.attachChild(scene);
        moving = quad("MovingFeature", .25f, .6f, ColorRGBA.White);
        moving.setLocalTranslation(-1.2f, -.3f, .1f);
        rootNode.attachChild(moving);
        Geometry edge = quad("StaticEdge", .7f, .7f, ColorRGBA.White);
        edge.setLocalTranslation(.55f, -.45f, .12f);
        edge.rotate(0, 0, .3f);
        rootNode.attachChild(edge);
        Geometry gui = quad("NativeGUI", 24, 24, ColorRGBA.Red);
        gui.setLocalTranslation(10, 10, 0);
        guiNode.attachChild(gui);
        fpp = new FilterPostProcessor(assetManager);
        fpp.setFrameBufferFormat(Image.Format.RGBA16F);
        fpp.setNumSamples(1);
        // Add the filters in reverse order to exercise FPP's stage ordering.
        fpp.addFilter(upscale);
        fpp.addFilter(fxaa);
        fpp.addFilter(taa);
        viewPort.addProcessor(fpp);
        configure();
    }

    private void configure() {
        if (taaCapture != null) {
            taaCapture.close();
            upscaleCapture.close();
            taaCapture = null;
            upscaleCapture = null;
        }
        viewPort.setRenderPath(paths[path]);
        upscale.setAlgorithm(stage == 1 || stage == 2 || stage >= 5
                ? UpscalingPass.Algorithm.FSR2 : UpscalingPass.Algorithm.FSR1);
        upscale.setBasePassOutputsVelocity(stage != SEPARATE_VELOCITY_STAGE);
        upscale.setRenderScale(stage == 2 ? 2f / 3f : .5f);
        upscale.setEnabled(stage != 3);
        if (stage == 5) {
            taaCapture = taa.suspendForCapture();
            upscaleCapture = upscale.suspendForCapture();
        }
        frame = 0;
        java.util.Arrays.fill(minimum, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(maximum, Float.NEGATIVE_INFINITY);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (moving != null) {
            moving.setLocalTranslation(-1.2f + .01f * ((frame + 1) % 48), -.3f, .1f);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        frame++;
        boolean accumulating = upscale.isEnabled() && upscale.replacesAntiAliasing();
        if (accumulating && frame >= 64 && frame <= 95) {
            ByteBuffer sample = BufferUtils.createByteBuffer(256 * 192 * 16);
            renderer.readFrameBufferWithFormat(output, sample, Image.Format.RGBA32F);
            int index = 0;
            for (int y = 60; y < 150; y++) {
                for (int x = 140; x < 220; x++) {
                    for (int c = 0; c < 3; c++) {
                        float value = sample.getFloat(((y * 256 + x) * 4 + c) * 4);
                        minimum[index] = Math.min(minimum[index], value);
                        maximum[index] = Math.max(maximum[index], value);
                        index++;
                    }
                }
            }
        }
        if (frame < (accumulating ? 95 : 12)) {
            return;
        }
        if (accumulating) {
            float worst = 0;
            for (int i = 0; i < minimum.length; i++) {
                worst = Math.max(worst, maximum[i] - minimum[i]);
            }
            if (!Float.isFinite(worst) || worst > .25f) {
                throw new AssertionError("FSR2 static edge discontinuity: " + worst);
            }
            System.out.println("FSR2_STABILITY_PASS " + paths[path] + "/" + stage + " range=" + worst);
        }
        float scale = upscale.isEnabled() ? upscale.getRenderScale() : 1;
        int expectedWidth = Math.round(256 * scale);
        if (viewPort.getOutputFrameBuffer().getWidth() != expectedWidth) {
            throw new AssertionError("Scene scale was not applied: " + viewPort.getOutputFrameBuffer().getWidth());
        }
        boolean temporal = upscale.isEnabled() && upscale.replacesAntiAliasing();
        if (stage == 5 && viewPort.getTemporalScene() != null) {
            throw new AssertionError("Capture preview still has temporal jitter/history");
        }
        if (fpp.isFilterBypassed(taa) != temporal || fpp.isFilterBypassed(fxaa) != temporal
                || taa.isEnabled() != (stage != 5) || !fxaa.isEnabled()) {
            throw new AssertionError("AA bypass/restoration changed user preferences");
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(256 * 192 * 4);
        renderer.readFrameBuffer(output, pixels);
        ByteBuffer floatPixels = BufferUtils.createByteBuffer(256 * 192 * 16);
        renderer.readFrameBufferWithFormat(output, floatPixels, Image.Format.RGBA32F);
        for (int i = 0; i < 256 * 192 * 4; i++) {
            if (!Float.isFinite(floatPixels.getFloat(i * 4))) {
                throw new AssertionError("Non-finite reconstructed output");
            }
        }
        int center = (96 * 256 + 128) * 4;
        int[] expected = {46, 51, 102};
        for (int i = 0; i < 3; i++) {
            if (Math.abs((pixels.get(center + i) & 255) - expected[i]) > 5) {
                throw new AssertionError("Reconstruction color " + paths[path] + "/" + stage + ": " + (pixels.get(center + i) & 255));
            }
        }
        if (Math.abs((pixels.get(center + 3) & 255) - 102) > 2) {
            throw new AssertionError("Source alpha was not preserved across reconstruction");
        }
        int inside = (20 * 256 + 20) * 4;
        int outside = (20 * 256 + 36) * 4;
        if ((pixels.get(inside) & 255) < 250 || (pixels.get(inside + 1) & 255) > 2
                || (pixels.get(outside + 1) & 255) < 10) {
            throw new AssertionError("GUI did not retain native output size");
        }
        if (temporal) {
            if (viewPort.getTemporalScene().isBasePass() != (stage != SEPARATE_VELOCITY_STAGE)) {
                throw new AssertionError("FSR2 did not use the requested opaque velocity pass");
            }
            Texture2D velocity = viewPort.getTemporalScene().getVelocity();
            FrameBuffer velocityBuffer = new FrameBuffer(velocity.getImage().getWidth(), velocity.getImage().getHeight(), 1);
            velocityBuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(velocity));
            ByteBuffer motionPixels = BufferUtils.createByteBuffer(velocity.getImage().getWidth() * velocity.getImage().getHeight() * 16);
            renderer.readFrameBufferWithFormat(velocityBuffer, motionPixels, Image.Format.RGBA32F);
            Vector3f screen = cam.getScreenCoordinates(moving.getLocalTranslation().add(.125f, .3f, 0));
            int x = Math.min(velocity.getImage().getWidth() - 1, Math.max(0, (int) (screen.x / 256 * velocity.getImage().getWidth())));
            int y = Math.min(velocity.getImage().getHeight() - 1, Math.max(0, (int) (screen.y / 192 * velocity.getImage().getHeight())));
            float motionX = motionPixels.getFloat((y * velocity.getImage().getWidth() + x) * 16);
            velocityBuffer.dispose();
            if (!Float.isFinite(motionX) || motionX <= .00001f) {
                throw new AssertionError("Moving FSR2 input lacks current-minus-previous motion: " + motionX);
            }
            Vector3f oldPosition = cam.getScreenCoordinates(new Vector3f(-1.05f, 0, .1f));
            int oldPixel = ((int) oldPosition.y * 256 + (int) oldPosition.x) * 4;
            if (Math.abs((pixels.get(oldPixel) & 255) - expected[0]) > 20) {
                throw new AssertionError("FSR2 retained a bright disoccluded moving feature");
            }
        }
        System.out.println("UPSCALING_STAGE_PASS " + paths[path] + "/" + stage);
        if (++stage > SEPARATE_VELOCITY_STAGE) {
            stage = 0;
            if (++path == paths.length) {
                System.out.println("UPSCALING_GPU_PASS");
                stop();
                return;
            }
        }
        configure();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}

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
package com.jme3.post.filters;

import com.jme3.asset.AssetManager;
import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector4f;
import com.jme3.post.*;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.framegraph.*;
import com.jme3.renderer.temporal.TemporalScene;
import com.jme3.renderer.upscaling.TemporalUpscalingBackend;
import com.jme3.texture.Texture2D;
import java.io.IOException;
import java.util.ServiceLoader;

/**
 * Upscales the scene before the GUI is drawn at native resolution. FSR1 takes
 * tone-mapped, anti-aliased color. FSR2 takes linear HDR and takes over from
 * TAA and FXAA. Enabling upscaling orders the supported filters by stage.
 * Disabling it restores filter insertion order.
 * Input color/depth/motion must match the current FPP render dimensions. Custom
 * filters must restore their pipeline phase size before reconstruction. RGB uses
 * the selected reconstruction; source alpha is resampled separately, not temporally
 * reconstructed. Custom effects default to scene-linear order 0; override getPipelineOrder()
 * when they belong after tone mapping or reconstruction. FSR2 uses FP32 compute,
 * low-resolution unjittered motion and perspective device depth. Optional reactive
 * masks must match render size; applications should supply masks for alpha effects
 * not represented by the nearest-layer motion/depth. Initial shader compilation
 * and resize rebuilds run on the render thread; warm up before interactive use.
 * GPU history is viewport-local and never serialized. FSR2 requires the LWJGL3
 * OpenGL compute backend; unavailable backends fail rather than substitute TAA.
 * All setters/attachment belong to the render thread. Resize, mode/scale changes
 * and re-enable rebuild targets/history. Call resetHistory for camera cuts.
 * <pre>{@code
 * // 第一步：开启浮点 FPP，设置单采样；输出尺寸由视口目标决定。
 * FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
 * fpp.setFrameBufferFormat(com.jme3.texture.Image.Format.RGBA16F);
 * fpp.setNumSamples(1);
 * // 第二步：添加所需后处理，再添加超分阶段。FSR2 自动旁路普通 TAA/FXAA。
 * UpscalingPass upscale = new UpscalingPass();
 * upscale.setAlgorithm(UpscalingPass.Algorithm.FSR2);
 * upscale.setRenderScale(2f / 3f);
 * // 默认在 GBuffer 或 Forward 主绘制输出运动矢量，可切换到独立 Pass。
 * upscale.setBasePassOutputsVelocity(false);
 * fpp.addFilter(upscale);
 * viewPort.addProcessor(fpp);
 * // 第三步：在渲染线程恢复主绘制输出，切换会重置历史；MRT 不足时自动走独立 Pass。
 * upscale.setBasePassOutputsVelocity(true);
 * // GUI 在独立 GUI 视口绘制；最终呈现只编码一次。
 * // 第四步：关闭超分恢复原生场景尺寸及先前抗锯齿选择，不清除用户参数。
 * upscale.setEnabled(false);
 * }</pre>
 */
public final class UpscalingPass extends HdrGraphFilter implements UpscalingStage {
    public enum Algorithm {
        FSR1,
        FSR2
    }
    private Algorithm algorithm = Algorithm.FSR1;
    private float renderScale = 2f / 3f;
    private float sharpness = .2f;
    private boolean basePassOutputsVelocity = true;
    private int outputWidth, outputHeight, sample;
    private float deltaSeconds;
    private boolean reset = true;
    private Material easu, rcas, copy;
    private TemporalScene temporal;
    private TemporalUpscalingBackend backend;
    private final Vector2f jitter = new Vector2f();
    private Texture2D reactiveMask, compositionMask;

    public UpscalingPass() {
        super("UpscalingPass");
    }

    @Override
    protected void onCaptureSuspended(boolean suspended) {
        resetHistory();
        if (temporal != null) {
            temporal.restoreCamera();
            if (view.getTemporalScene() == temporal) {
                view.setTemporalScene(null);
            }
        }
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }
    public void setAlgorithm(Algorithm value) {
        algorithm = java.util.Objects.requireNonNull(value);
        resetHistory();
    }
    @Override
    public float getRenderScale() {
        return renderScale;
    }
    /** Sets the scene scale from 0.25 to 1. The default is 2/3. Output and GUI stay at native resolution. */
    public void setRenderScale(float value) {
        renderScale = bounded(value, .25f, 1, "Render scale");
        resetHistory();
    }
    public float getSharpness() {
        return sharpness;
    }
    public void setSharpness(float value) {
        sharpness = bounded(value, 0, 1, "Sharpness");
    }

    /**
     * Selects the opaque motion-vector producer used by FSR2. By default the
     * renderer writes motion with the GBuffer or forward color when supported.
     * Passing false selects the separate opaque pass. Insufficient MRT support
     * also selects the separate pass. Change on the render thread between frames.
     * Switching resets FSR2 history. FSR1 never uses this setting.
     *
     * @param value true to prefer the base pass, false for the separate pass
     */
    public void setBasePassOutputsVelocity(boolean value) {
        if (basePassOutputsVelocity != value) {
            basePassOutputsVelocity = value;
            if (algorithm == Algorithm.FSR2) {
                resetHistory();
            }
        }
    }

    /** @return the preferred opaque velocity mode, before any renderer fallback */
    public boolean isBasePassOutputsVelocity() {
        return basePassOutputsVelocity;
    }

    /** Optional linear masks owned by the caller. Pass null to use zero masks. */
    public void setReactiveMasks(Texture2D reactive, Texture2D composition) {
        reactiveMask = reactive;
        compositionMask = composition;
    }
    public void resetHistory() {
        reset = true;
        sample = 0;
        if (temporal != null) {
            temporal.resetHistory();
        }
    }
    @Override
    public boolean replacesAntiAliasing() {
        return algorithm == Algorithm.FSR2;
    }
    @Override
    public int getPipelineOrder() {
        return replacesAntiAliasing() ? 20 : 70;
    }
    @Override
    public void setOutputSize(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Output size must be positive");
        }
        outputWidth = width;
        outputHeight = height;
    }
    @Override
    protected boolean isRequiresDepthTexture() {
        return replacesAntiAliasing();
    }

    @Override
    protected void initialize() {
        release();
        resetHistory();
        if (!manager.getRenderer().getCaps().contains(com.jme3.renderer.Caps.GLSL430)) {
            throw new UnsupportedOperationException("FSR adapters require desktop GLSL430");
        }
        if (format == null) {
            throw new UnsupportedOperationException("Upscaling requires float color targets");
        }
        if (outputWidth < 1 || outputHeight < 1) {
            outputWidth = width;
            outputHeight = height;
        }
        copy = new Material(assets, "Common/MatDefs/Post/Upscaling/Fsr2Output.j3md");
        if (replacesAntiAliasing()) {
            for (TemporalUpscalingBackend candidate : ServiceLoader.load(TemporalUpscalingBackend.class)) {
                if (candidate.getAlgorithmId().equals(algorithm.name()) && candidate.supports(manager.getRenderer())) {
                    backend = candidate;
                    break;
                }
            }
            if (backend == null) {
                throw new UnsupportedOperationException("No FSR2 backend supports this renderer");
            }
            temporal = new TemporalScene(assets, manager, view);
        } else {
            easu = new Material(assets, "Common/MatDefs/Post/Upscaling/Fsr1Easu.j3md");
            rcas = new Material(assets, "Common/MatDefs/Post/Upscaling/Fsr1Rcas.j3md");
        }
    }

    private static float halton(int index, int radix) {
        float value = 0;
        float fraction = 1;
        while (index > 0) {
            fraction /= radix;
            value += fraction * (index % radix);
            index /= radix;
        }
        return value;
    }

    @Override
    protected void preFrame(float tpf) {
        deltaSeconds = tpf;
        if (temporal != null) {
            view.setTemporalScene(temporal);
            temporal.configure(basePassOutputsVelocity, true, .5f);
            int phases = Math.max(1, (int) (8.0 * outputWidth * outputWidth / ((double) width * width)));
            int index = sample++ % phases + 1;
            jitter.set(halton(index, 2) - .5f, halton(index, 3) - .5f);
            temporal.begin(width, height, jitter);
        }
    }

    @Override
    protected ResourceHandle<RenderTarget> build(FilterGraphContext context, ResourceHandle<RenderTarget> input) {
        ResourceHandle<RenderTarget> output = context.createColor("Output", outputWidth, outputHeight, format);
        if (!replacesAntiAliasing()) {
            easu.setVector4("Con0", new Vector4f((float) width / outputWidth, (float) height / outputHeight,
                    .5f * width / outputWidth - .5f, .5f * height / outputHeight - .5f));
            easu.setVector4("Con1", new Vector4f(1f / width, 1f / height, 1f / width, -1f / height));
            easu.setVector4("Con2", new Vector4f(-1f / width, 2f / height, 1f / width, 2f / height));
            easu.setVector4("Con3", new Vector4f(0, 4f / height, 0, 0));
            rcas.setFloat("Sharpness", sharpness);
            ResourceHandle<RenderTarget> edge = context.createColor("EASU", outputWidth, outputHeight, format);
            context.draw("FSR1/EASU", easu, input, edge);
            context.draw("FSR1/RCAS", rcas, edge, output);
            return output;
        }
        temporal.restoreCamera();
        var graph = context.getGraph();
        var velocity = graph.importResource(context.name("Velocity"), Texture2D.class, temporal::getVelocity);
        var depth = graph.importResource(context.name("MotionDepth"), Texture2D.class, temporal::getVelocityDepth);
        var masks = graph.importResource(context.name("ReactiveMask"), Texture2D.class, () -> reactiveMask);
        var compositions = graph.importResource(context.name("CompositionMask"), Texture2D.class, () -> compositionMask);
        graph.addPass(context.name("FSR2/Reconstruct"), pass -> {
            Texture2D result = backend.reconstruct(manager, pass.get(input).getColorTexture(0),
                    pass.get(depth), pass.get(velocity), pass.get(masks), pass.get(compositions),
                    view.getCamera(), jitter, deltaSeconds, outputWidth, outputHeight, sharpness,
                    reset || temporal.isReset());
            copy.setTexture("SourceAlpha", pass.get(input).getColorTexture(0));
            context.drawResolved(copy, result, pass.get(output).getFrameBuffer());
            temporal.commit();
            reset = false;
        }).read(input).read(depth).read(velocity).read(masks).read(compositions).write(output);
        return output;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != isEnabled()) {
            resetHistory();
            if (!enabled && temporal != null) {
                temporal.restoreCamera();
                if (view.getTemporalScene() == temporal) {
                    view.setTemporalScene(null);
                }
            }
        }
        super.setEnabled(enabled);
    }

    private void release() {
        if (temporal != null) {
            temporal.close();
            temporal = null;
        }
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }
    @Override
    protected void cleanUpFilter(com.jme3.renderer.Renderer renderer) {
        release();
    }
    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var capsule = exporter.getCapsule(this);
        capsule.write(algorithm, "algorithm", Algorithm.FSR1);
        capsule.write(renderScale, "renderScale", 2f / 3f);
        capsule.write(sharpness, "sharpness", .2f);
        capsule.write(basePassOutputsVelocity, "basePassOutputsVelocity", true);
    }
    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var capsule = importer.getCapsule(this);
        algorithm = capsule.readEnum("algorithm", Algorithm.class, Algorithm.FSR1);
        setRenderScale(capsule.readFloat("renderScale", 2f / 3f));
        setSharpness(capsule.readFloat("sharpness", .2f));
        setBasePassOutputsVelocity(capsule.readBoolean("basePassOutputsVelocity", true));
    }
}

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
package com.jme3.renderer.upscaling;

import com.jme3.math.Vector2f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.texture.Texture2D;

/**
 * Backend-owned temporal reconstruction. All calls belong to the render thread.
 * Applications normally add UpscalingPass to FPP; this SPI is a low-level backend
 * extension. Providers identify their algorithm and own their persistent GPU state.
 * <pre>{@code
 * // 第一步：低层扩展在渲染线程选择支持当前 Renderer 的 FSR2 提供者。
 * TemporalUpscalingBackend backend = java.util.ServiceLoader.load(TemporalUpscalingBackend.class)
 *         .stream().map(java.util.ServiceLoader.Provider::get)
 *         .filter(value -> value.getAlgorithmId().equals("FSR2") && value.supports(renderer))
 *         .findFirst().orElseThrow(() -> new UnsupportedOperationException("FSR2 unavailable"));
 * // 第二步：输入尺寸匹配，深度与速度对应同一表面；输出仍为线性 HDR。
 * Texture2D result = backend.reconstruct(renderManager, color, depth, motion, null, null,
 *         camera, jitterPixels, tpf, outputWidth, outputHeight, .2f, resetHistory);
 * // 第三步：先复制/消费结果，最后在同一渲染线程 close；不得把 result 留到关闭以后。
 * backend.close();
 * }</pre>
 */
public interface TemporalUpscalingBackend extends AutoCloseable {
    /** Stable reconstruction identifier used by the stage's provider selection. */
    String getAlgorithmId();

    boolean supports(Renderer renderer);

    /**
     * Reconstructs unencoded linear HDR color. Motion is current-minus-previous
     * unjittered UV; RGBA motion may use A=0 for invalid history (two-channel
     * textures have implicit A=1). Invalid motion is sanitized and made reactive.
     * Depth is ordinary non-reversed device depth in [0,1].
     * Masks are optional linear [0,1] textures. Returned storage belongs to this
     * backend until resize/close; callers must not destroy or retain it afterward.
     */
    Texture2D reconstruct(RenderManager manager, Texture2D color, Texture2D depth,
            Texture2D motion, Texture2D reactive, Texture2D composition,
            Camera camera, Vector2f jitterPixels, float deltaSeconds,
            int outputWidth, int outputHeight, float sharpness, boolean reset);

    @Override
    void close();
}

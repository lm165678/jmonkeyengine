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
package com.jme3.renderer.deferred;

import com.jme3.asset.AssetManager;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import java.util.Objects;

/**
 * Backward-compatible SceneProcessor facade. New integrations may install
 * DeferredPipeline directly through RenderManager.setPipeline(viewport, pipeline).
 */
public final class TiledDeferredProcessor implements com.jme3.post.SceneProcessor, RenderPipeline {
    public enum Mode { Forward, TiledDeferredCPU, TiledDeferredGPU, Auto, Deferred }
    private final DeferredPipeline pipeline;
    private Mode mode = Mode.Forward;

    public TiledDeferredProcessor(AssetManager assets) { this(assets, new ShadingModelRegistry()); }
    public TiledDeferredProcessor(AssetManager assets, ShadingModelRegistry models) {
        pipeline = new DeferredPipeline(assets, models);
        setMode(Mode.Forward);
    }
    public DeferredPipeline getPipeline() { return pipeline; }
    public void setTileSize(int size) { pipeline.setTileSize(size); }
    public int getTileSize() { return pipeline.getTileSize(); }
    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode);
        pipeline.setEnabled(mode != Mode.Forward);
        switch (mode) {
            case Deferred: pipeline.setLightCullingMode(LightCullingMode.None); break;
            case TiledDeferredCPU: pipeline.setLightCullingMode(LightCullingMode.CPU); break;
            case TiledDeferredGPU: pipeline.setLightCullingMode(LightCullingMode.GPU); break;
            default: pipeline.setLightCullingMode(LightCullingMode.Auto);
        }
    }
    public Mode getMode() { return mode; }
    public Mode getActiveMode() { return Mode.valueOf(pipeline.getActivePath().name()); }
    public int getDeferredGeometryCount() { return pipeline.getDeferredGeometryCount(); }
    public int getForwardGeometryCount() { return pipeline.getForwardGeometryCount(); }
    public String getFallbackReason() { return pipeline.getFallbackReason(); }
    public void registerShadingModel(int id, String source) { pipeline.getShadingModels().register(id, source); }
    @Override public void initialize(RenderManager manager, ViewPort viewport) { pipeline.initialize(manager, viewport); }
    @Override public boolean isInitialized() { return pipeline.isInitialized(); }
    @Override public void reshape(int width, int height) { pipeline.reshape(width, height); }
    @Override public void reshape(ViewPort viewport, int width, int height) { reshape(width, height); }
    @Override public void render(RenderQueue queue) { pipeline.render(queue); }
    @Override public void postQueue(RenderQueue queue) { render(queue); }
    @Override public void preFrame(float tpf) { }
    @Override public void postFrame(FrameBuffer output) { }
    @Override public void setProfiler(com.jme3.profile.AppProfiler profiler) { }
    @Override public void cleanup() { pipeline.cleanup(); }
}

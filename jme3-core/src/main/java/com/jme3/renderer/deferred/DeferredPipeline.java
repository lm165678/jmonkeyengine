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
import com.jme3.light.AmbientLight;
import com.jme3.light.DefaultLightFilter;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.LightList;
import com.jme3.light.LightProbe;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.RenderState;
import com.jme3.material.TechniqueDef;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.texture.Texture;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Deferred opaque pipeline. Passes operate on borrowed frame data; resources
 * belong to this viewport alone. Culling policy is separate from pipeline type.
 */
public final class DeferredPipeline implements RenderPipeline {
    private final AssetManager assets;
    private final ShadingModelRegistry models;
    private final List<Geometry> forward = new ArrayList<>();
    private final List<DeferredBatch> batches = new ArrayList<>();
    private final DeferredPass cullingPass = new LightCullingPass();
    private final DeferredPass surfacePass = new GBufferPass();
    private final DeferredPass lightingPass = new DeferredLightingPass();
    private DeferredRenderContext frame;
    private RenderManager rm;
    private ViewPort vp;
    private Renderer renderer;
    private int batchCount, deferredCount, forwardCount;
    private int tileSize = 16;
    private boolean enabled = true;
    private LightCullingMode cullingMode = LightCullingMode.Auto;
    private RenderPath activePath = RenderPath.Forward;
    private String fallbackReason = "";

    public DeferredPipeline(AssetManager assets) { this(assets, new ShadingModelRegistry()); }
    public DeferredPipeline(AssetManager assets, ShadingModelRegistry models) {
        this.assets = Objects.requireNonNull(assets);
        this.models = Objects.requireNonNull(models);
    }
    public ShadingModelRegistry getShadingModels() { return models; }
    public void setLightCullingMode(LightCullingMode mode) { cullingMode = Objects.requireNonNull(mode); }
    public LightCullingMode getLightCullingMode() { return cullingMode; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setTileSize(int size) {
        if (size < 1 || size > 256) throw new IllegalArgumentException("Tile size must be 1..256");
        tileSize = size;
    }
    public int getTileSize() { return tileSize; }
    public RenderPath getActivePath() { return activePath; }
    public int getDeferredGeometryCount() { return deferredCount; }
    public int getForwardGeometryCount() { return forwardCount; }
    public String getFallbackReason() { return fallbackReason; }

    @Override public void initialize(RenderManager manager, ViewPort viewport) {
        if (frame != null) throw new IllegalStateException("Pipeline already initialized");
        rm = Objects.requireNonNull(manager);
        vp = Objects.requireNonNull(viewport);
        renderer = manager.getRenderer();
        frame = new DeferredRenderContext(rm, vp, new DeferredResources(assets, renderer), models);
    }
    @Override public boolean isInitialized() { return frame != null; }
    @Override public void reshape(int width, int height) {
        if (frame != null) frame.resources.releaseTargets();
    }
    private boolean supported(Geometry geometry) {
        Material material = geometry.getMaterial();
        MaterialDef def = material.getMaterialDef();
        int id = def.getShadingId();
        if (id == 0 || !models.supports(id)
                || def.getTechniqueDefs("GBuffer") == null || material.isTransparent()) return false;
        RenderState state = material.getAdditionalRenderState();
        if (state.getBlendMode() != RenderState.BlendMode.Off || state.isWireframe()
                || !state.isDepthTest() || !state.isDepthWrite() || !state.isColorWrite()
                || state.isStencilTest() || state.isPolyOffset()
                || state.getDepthFunc() != RenderState.TestFunction.LessOrEqual) return false;
        if (material.getActiveTechnique() != null
                && !TechniqueDef.DEFAULT_TECHNIQUE_NAME.equals(material.getActiveTechnique().getDef().getName())) return false;
        // A GBuffer technique explicitly opts into the surface contract for its model ID.
        return true;
    }

    private boolean supportedLights(LightList list) {
        for (int i = 0; i < list.size(); i++) {
            Light l = list.get(i);
            if (!(l instanceof AmbientLight || l instanceof DirectionalLight
                    || l instanceof PointLight || l instanceof SpotLight || l instanceof LightProbe)) return false;
        }
        return true;
    }

    @Override public void render(RenderQueue queue) {
        if (frame == null) throw new IllegalStateException("Pipeline not initialized");
        try {
            renderFrame(queue);
        } finally {
            // Retain list capacity, not scene objects or material textures.
            for (DeferredBatch batch : batches) batch.reset();
            forward.clear();
            frame.lights.clear();
        }
    }

    private void renderFrame(RenderQueue queue) {
        deferredCount = 0; forwardCount = queue.getOpaqueQueue().size();
        activePath = RenderPath.Forward; fallbackReason = "";
        if (!enabled) { frame.resources.releaseTargets(); return; }
        renderer.setFrameBuffer(vp.getOutputFrameBuffer());
        if (renderer.getCurrentFrameBufferSamples() > 1) {
            fallbackReason = "Multisampled targets retain forward rendering; use FXAA for deferred";
            return;
        }
        Camera camera = vp.getCamera();
        if (camera.getWidth() <= 0 || camera.getHeight() <= 0) {
            fallbackReason = "Viewport has no drawable area"; return;
        }
        EnumSet<Caps> caps = renderer.getCaps();
        if (!caps.containsAll(EnumSet.of(Caps.GLSL330, Caps.FrameBufferMRT, Caps.FloatColorBuffer))
                || renderer.getLimits().get(Limits.FrameBufferMrtAttachments) < 5) {
            fallbackReason = "Requires GLSL330 and five floating-point MRT attachments"; return;
        }
        if (camera.getViewPortLeft() != 0 || camera.getViewPortBottom() != 0
                || camera.getViewPortRight() != 1 || camera.getViewPortTop() != 1
                || !vp.isClearDepth() || rm.getForcedTechnique() != null
                || rm.getForcedMaterial() != null
                || (vp.getOutputFrameBuffer() != null && vp.getOutputFrameBuffer().getSamples() > 1)
                || rm.getForcedRenderState() != null || !rm.getForcedMatParams().isEmpty()
                || rm.getLightFilter() == null || rm.getLightFilter().getClass() != DefaultLightFilter.class) {
            fallbackReason = "Viewport or render overrides require forward rendering"; return;
        }
        forward.clear();
        batchCount = 0;
        GeometryList opaque = queue.getOpaqueQueue();
        for (Geometry geometry : opaque) {
            LightList worldLights = geometry.getWorldLightList();
            if (!supported(geometry) || !supportedLights(worldLights)) {
                forward.add(geometry);
                continue;
            }
            Texture ramp = (Texture) GBufferPass.parameter(geometry, "ColorRamp");
            DeferredBatch selected = null;
            for (int i = 0; i < batchCount; i++) {
                if (batches.get(i).matches(worldLights, ramp)) {
                    selected = batches.get(i);
                    break;
                }
            }
            if (selected == null) {
                if (batchCount == batches.size()) batches.add(new DeferredBatch());
                selected = batches.get(batchCount++);
                selected.reset();
                selected.colorRamp = ramp;
                for (Light light : worldLights) {
                    if (!(light instanceof LightProbe)) {
                        selected.lights.add(light);
                        selected.identities.add(light);
                    }
                }
            }
            selected.geometries.add(geometry);
        }
        for (int i = 0; i < batchCount; i++) {
            DeferredBatch batch = batches.get(i);
            if (renderBatch(batch)) deferredCount += batch.geometries.size();
            else forward.addAll(batch.geometries);
        }
        opaque.clear();
        for (Geometry geometry : forward) opaque.add(geometry);
        forwardCount = forward.size();
    }

    private boolean renderBatch(DeferredBatch batch) {
        frame.batch = batch;
        frame.tileSize = tileSize;
        frame.cullingMode = cullingMode;
        String oldTechnique = rm.getForcedTechnique();
        try {
            if (!cullingPass.execute(frame)) {
                fallbackReason = frame.fallbackReason;
                return false;
            }
            if (!frame.fallbackReason.isEmpty()) fallbackReason = frame.fallbackReason;
            frame.resources.ensureTargets(vp.getCamera().getWidth(), vp.getCamera().getHeight());
            activePath = frame.untiled ? RenderPath.Deferred
                    : frame.gpu ? RenderPath.TiledDeferredGPU : RenderPath.TiledDeferredCPU;
            surfacePass.execute(frame);
            lightingPass.execute(frame);
            return true;
        } finally {
            rm.setForcedTechnique(oldTechnique);
            renderer.setFrameBuffer(vp.getOutputFrameBuffer());
            renderer.setBackgroundColor(vp.getBackgroundColor());
            rm.setCamera(vp.getCamera(), false);
            frame.batch = null;
        }
    }
    @Override public void cleanup() {
        if (frame != null) frame.resources.cleanup();
        surfacePass.reset();
        cullingPass.reset();
        frame = null;
        rm = null;
        vp = null;
        renderer = null;
        batches.clear();
        forward.clear();
        batchCount = deferredCount = forwardCount = 0;
        activePath = RenderPath.Forward;
        fallbackReason = "";
    }
}

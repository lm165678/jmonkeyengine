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
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.RenderTargetDefinition;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
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
 *
 * <p>Install from {@code SimpleApplication.simpleInitApp()} or another render-thread
 * callback. Share the manager's registry to reuse its custom shading models:</p>
 * <pre>{@code
 * DeferredPipeline pipeline = new DeferredPipeline(
 *         assetManager, renderManager.getDeferredShadingModels());
 * pipeline.setLightCullingMode(LightCullingMode.GPU);
 * pipeline.setTileSize(16);
 * renderManager.setPipeline(viewPort, pipeline);
 *
 * // Change the backend without replacing the pipeline.
 * pipeline.setLightCullingMode(LightCullingMode.CPU);
 * pipeline.setLightCullingMode(LightCullingMode.Auto);
 *
 * // Explicitly use Forward for this viewport.
 * renderManager.setPipeline(viewPort, new ForwardPipeline());
 *
 * // Or return control to RenderPath selection and select Forward.
 * renderManager.setPipeline(viewPort, null);
 * viewPort.setRenderPath(RenderPath.Inherit);
 * renderManager.setRenderPath(RenderPath.Forward);
 * }</pre>
 * <p>{@code ForwardPipeline} is in {@code com.jme3.renderer.pipeline};
 * {@code RenderPath} is in {@code com.jme3.renderer}. The manager initializes,
 * resizes and cleans up the installed pipeline. Do not call these lifecycle
 * methods yourself while it is installed, or share an instance across viewports.
 * A directly installed pipeline takes precedence over RenderPath selection.</p>
 * @see RenderManager#setPipeline(ViewPort, RenderPipeline)
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
    private FrameGraph graph;
    private RenderManager rm;
    private ViewPort vp;
    private Renderer renderer;
    private int batchCount, deferredCount, forwardCount;
    private int tileSize = 16;
    private boolean enabled = true;
    private LightCullingMode cullingMode = LightCullingMode.Auto;
    private RenderPath activePath = RenderPath.Forward;
    private String fallbackReason = "";

    /**
     * Creates a pipeline with a private model registry, default Auto culling and
     * 16-pixel tiles. Use the two-argument constructor to share custom models.
     * @param assets non-null asset manager used to load pipeline shaders
     */
    public DeferredPipeline(AssetManager assets) {
        this(assets, new ShadingModelRegistry());
    }
    /**
     * Creates a pipeline using an existing registry, without allocating GPU resources.
     * Example: {@code new DeferredPipeline(assetManager,
     * renderManager.getDeferredShadingModels())}. Install with RenderManager.setPipeline; the
     * manager owns its lifecycle.
     * @param assets non-null asset manager
     * @param models non-null registry, which may be shared by multiple pipelines
     */
    public DeferredPipeline(AssetManager assets, ShadingModelRegistry models) {
        this.assets = Objects.requireNonNull(assets);
        this.models = Objects.requireNonNull(models);
    }
    /**
     * Returns the shared viewport graph for read-only execution-order/pool diagnostics.
     * Do not execute, clear or close it while this pipeline is installed.
     * @return shared graph after pass registration, otherwise null
     */
    public FrameGraph getFrameGraph() {
        return graph;
    }
    public ShadingModelRegistry getShadingModels() {
        return models;
    }
    /**
     * Selects light-list construction on the render thread, for example
     * {@code pipeline.setLightCullingMode(LightCullingMode.GPU)}.
     * GPU and Auto can fall back to CPU; unsupported deferred viewport states
     * use Forward. After rendering, inspect {@link #getActivePath()} and
     * {@link #getFallbackReason()} rather than assuming the request was used.
     * None still uses deferred lighting, but does not build tiled light lists.
     * @param mode non-null requested culling policy
     */
    public void setLightCullingMode(LightCullingMode mode) {
        cullingMode = Objects.requireNonNull(mode);
    }
    public LightCullingMode getLightCullingMode() {
        return cullingMode;
    }
    /**
     * Enables deferred rendering or leaves the opaque queue to Forward when false.
     * Example: {@code pipeline.setEnabled(false)} temporarily bypasses this pipeline
     * without releasing its resources or removing its viewport override.
     * @param enabled true to render supported opaque geometry through deferred passes
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    /**
     * Sets tile width and height in pixels on the render thread.
     * Example: {@code pipeline.setTileSize(16)}. Values need not be powers of two.
     * Tune against representative scenes; smaller tiles can increase CPU binning
     * cost. RenderManager.setDeferredTileSize does not configure this directly
     * installed pipeline.
     * @param size tile dimension, from 1 through 256 inclusive
     */
    public void setTileSize(int size) {
        if (size < 1 || size > 256) {
            throw new IllegalArgumentException("Tile size must be 1..256");
        }
        tileSize = size;
    }
    public int getTileSize() {
        return tileSize;
    }
    /**
     * Returns the last reported concrete path, initially Forward. Query after the
     * viewport has rendered (for example in a SceneProcessor.postFrame callback).
     * This is not a per-geometry report: unsupported geometry can still use Forward,
     * and multiple independent light scopes can choose different culling backends.
     * @return most recently reported rendering path
     */
    public RenderPath getActivePath() {
        return activePath;
    }
    /** @return opaque geometries consumed by deferred passes in the latest render */
    public int getDeferredGeometryCount() {
        return deferredCount;
    }
    /** @return opaque geometries left for Forward; excludes transparent/GUI queues */
    public int getForwardGeometryCount() {
        return forwardCount;
    }
    /**
     * Returns the current diagnostic text, or an empty string if none was recorded.
     * Query after rendering alongside getActivePath and the geometry counts.
     * This is not an aggregate reason list for all geometry/light scopes, and an
     * empty string does not imply every geometry was rendered through deferred.
     * @return fallback diagnostic, never null
     */
    public String getFallbackReason() {
        return fallbackReason;
    }

    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        if (frame != null) {
            throw new IllegalStateException("Pipeline already initialized");
        }
        rm = Objects.requireNonNull(manager);
        vp = Objects.requireNonNull(viewport);
        renderer = manager.getRenderer();
        frame = new DeferredRenderContext(rm, vp, new DeferredResources(assets, renderer), models);
    }
    @Override
    public boolean isInitialized() {
        return frame != null;
    }
    @Override
    public void reshape(int width, int height) {
        if (frame != null) {
            frame.resources.releaseTargets();
        }

    }
    private boolean supported(Geometry geometry) {
        Material material = geometry.getMaterial();
        MaterialDef def = material.getMaterialDef();
        int id = models.resolve(def, assets);
        if (id == 0 || !models.supports(id) || def.getTechniqueDefs("GBuffer") == null
            || material.isTransparent()) {
            return false;
        }
        RenderState state = material.getAdditionalRenderState();
        if (state.getBlendMode() != RenderState.BlendMode.Off || state.isWireframe()
            || !state.isDepthTest() || !state.isDepthWrite() || !state.isColorWrite()
            || state.isStencilTest() || state.isPolyOffset()
            || state.getDepthFunc() != RenderState.TestFunction.LessOrEqual) {
            return false;
        }
        if (material.getActiveTechnique() != null
            && !TechniqueDef.DEFAULT_TECHNIQUE_NAME.equals(
                material.getActiveTechnique().getDef().getName())) {
            return false;
        }
        // A GBuffer technique explicitly opts into the surface contract for its model ID.
        return true;
    }

    private boolean supportedLights(LightList list) {
        for (int i = 0; i < list.size(); i++) {
            Light l = list.get(i);
            if (!(l instanceof AmbientLight || l instanceof DirectionalLight
                    || l instanceof PointLight || l instanceof SpotLight
                    || l instanceof LightProbe)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void render(RenderQueue queue) {
        try (FrameGraph standalone = new FrameGraph()) {
            standalone.addExpansionPass("Deferred", current -> appendPasses(current, queue));
            standalone.execute(rm, vp, queue);
        }
        graph = null;
    }

    @Override
    public void appendPasses(FrameGraph graph, RenderQueue queue) {
        if (frame == null) {
            throw new IllegalStateException("Pipeline not initialized");
        }
        this.graph = graph; // Borrowed viewport graph; never close/clear it from this pipeline.
        renderFrame(queue);
        graph.addPass("ReleaseFrameReferences", context -> {
            for (DeferredBatch batch : batches) {
                batch.reset();
            }
            forward.clear();
            frame.lights.clear();
        }).sideEffect();
    }

    private void renderFrame(RenderQueue queue) {
        deferredCount = 0;
        forwardCount = queue.getOpaqueQueue().size();
        activePath = RenderPath.Forward;
        fallbackReason = "";
        if (!enabled) {
            frame.resources.releaseTargets();
            return;
        }
        renderer.setFrameBuffer(vp.getOutputFrameBuffer());
        if (renderer.getCurrentFrameBufferSamples() > 1) {
            fallbackReason = "Multisampled targets retain forward rendering; use FXAA for deferred";
            return;
        }
        Camera camera = vp.getCamera();
        if (camera.getWidth() <= 0 || camera.getHeight() <= 0) {
            fallbackReason = "Viewport has no drawable area";
            return;
        }
        EnumSet<Caps> caps = renderer.getCaps();
        if (!caps.containsAll(EnumSet.of(Caps.GLSL330, Caps.FrameBufferMRT, Caps.FloatColorBuffer))
            || renderer.getLimits().get(Limits.FrameBufferMrtAttachments) < 5) {
            fallbackReason = "Requires GLSL330 and five floating-point MRT attachments";
            return;
        }
        if (camera.getViewPortLeft() != 0 || camera.getViewPortBottom() != 0
            || camera.getViewPortRight() != 1 || camera.getViewPortTop() != 1 || !vp.isClearDepth()
            || rm.getForcedTechnique() != null || rm.getForcedMaterial() != null
            || (vp.getOutputFrameBuffer() != null && vp.getOutputFrameBuffer().getSamples() > 1)
            || rm.getForcedRenderState() != null || !rm.getForcedMatParams().isEmpty()
            || rm.getLightFilter() == null
            || rm.getLightFilter().getClass() != DefaultLightFilter.class) {
            fallbackReason = "Viewport or render overrides require forward rendering";
            return;
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
                if (batchCount == batches.size()) {
                    batches.add(new DeferredBatch());
                }
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
            appendBatch(batches.get(i), i);
        }
        graph.addPass("ForwardFallbackQueue", context -> {
            opaque.clear();
            for (Geometry geometry : forward) {
                opaque.add(geometry);
            }
            forwardCount = forward.size();
        }).sideEffect();
    }

    private void appendBatch(DeferredBatch batch, int index) {
        String name = "Batch[" + index + "]/";
        boolean[] ready = {false};
        ResourceHandle<RenderTarget> surfaces = graph.createResource(name + "GBuffer",
            new RenderTargetDefinition(1f, 1, Image.Format.Depth24, Image.Format.RGBA32F,
                Image.Format.RGBA16F, Image.Format.RGBA16F, Image.Format.RGBA16F, Image.Format.RGBA16F));
        ResourceHandle<FrameBuffer> output = graph.importResource(name + "Output",
            FrameBuffer.class, () -> vp.getOutputFrameBuffer());
        graph.addPass(name + "LightCulling", context -> {
            frame.batch = batch;
            frame.tileSize = tileSize;
            frame.cullingMode = cullingMode;
            ready[0] = cullingPass.execute(frame);
            if (!frame.fallbackReason.isEmpty()) {
                fallbackReason = frame.fallbackReason;
            }
            if (!ready[0]) {
                forward.addAll(batch.geometries);
            }
        }).sideEffect();
        graph.addPass(name + "GBuffer", context -> {
            if (ready[0]) {
                frame.resources.borrowTargets(context.get(surfaces));
                surfacePass.execute(frame);
            }
        }).write(surfaces);
        graph.addPass(name + "Lighting", context -> {
            if (ready[0]) {
                context.get(surfaces);
                context.get(output);
                lightingPass.execute(frame);
                deferredCount += batch.geometries.size();
                activePath = frame.untiled ? RenderPath.Deferred
                    : frame.gpu ? RenderPath.TiledDeferredGPU : RenderPath.TiledDeferredCPU;
            }
        }).read(surfaces).write(output);
        graph.addPass(name + "Restore", context -> {
            renderer.setFrameBuffer(vp.getOutputFrameBuffer());
            renderer.setBackgroundColor(vp.getBackgroundColor());
            rm.setCamera(vp.getCamera(), false);
            frame.resources.releaseTargets();
            frame.batch = null;
        }).sideEffect();
    }
    @Override
    public void cleanup() {
        graph = null;
        if (frame != null) {
            frame.resources.cleanup();
        }
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

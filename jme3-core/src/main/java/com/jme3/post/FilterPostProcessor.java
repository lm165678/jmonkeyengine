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
package com.jme3.post;

import com.jme3.asset.AssetManager;
import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;
import com.jme3.export.Savable;
import com.jme3.material.Material;
import com.jme3.profile.AppProfiler;
import com.jme3.profile.SpStep;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.FullscreenTriangle;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.FrameBuffer.FrameBufferTarget;
import com.jme3.texture.Image.Format;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.ui.Picture;
import com.jme3.util.SafeArrayList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A `FilterPostProcessor` is a {@link SceneProcessor} that can apply several
 * {@link Filter}s to a rendered scene. It manages a list of filters that will be
 * applied in the order in which they have been added. This processor handles
 * rendering the main scene to an offscreen framebuffer, then applying each enabled
 * filter sequentially, optionally with anti-aliasing (multisampling) and depth texture
 * support.
 *
 * <p>For graph-native multi-pass effects, add a {@link FrameGraphFilter} to this
 * same list. Its build callback declares passes in the shared viewport graph;
 * legacy filters retain their existing lifecycle. Graph-native temporary targets
 * are pooled by the graph, while legacy Filter.Pass targets remain filter-owned.
 * See FrameGraphFilter and TestFrameGraphFilters for step-by-step usage.</p>
 * @author Rémy Bouquet aka Nehon
 */
public class FilterPostProcessor implements SceneProcessor, Savable {

    private FrameGraph filterGraph;
    private Material graphCopyMaterial;

    /**
     * Returns the shared viewport graph after this processor has registered passes.
     * Filter order is preserved, including custom callback side effects. Targets
     * from legacy Filter owners are imported; native transient targets are graph-owned.
     * <pre>{@code
     * // 第一步：按原有接口添加 Filter，不需要手动建图。
     * fpp.addFilter(new BloomFilter());
     * viewPort.addProcessor(fpp);
     * // 第二步：渲染后查看每个 Filter 的图节点；开启/关闭将在下一帧生效。
     * System.out.println(fpp.getFrameGraph().getExecutedPasses());
     * }</pre>
     * @return shared viewport graph, or null before registration; do not mutate it
     */
    public FrameGraph getFrameGraph() {
        return filterGraph;
    }

    private static final class FilterChainState {
        Texture2D texture;
        FrameBuffer buffer;
    }

    public static final String FPP = FilterPostProcessor.class.getSimpleName();

    private RenderManager renderManager;
    private Renderer renderer;
    private ViewPort viewPort;
    private FrameBuffer renderFrameBufferMS;
    private int numSamples = 1;
    private FrameBuffer renderFrameBuffer;
    private Texture2D filterTexture;
    private Texture2D depthTexture;
    private SafeArrayList<Filter> filters = new SafeArrayList<>(Filter.class);
    private AssetManager assetManager;
    private boolean useFullscreenTriangle = false;
    private Geometry fsQuad;
    private boolean computeDepth = false;
    private FrameBuffer outputBuffer;
    private int width;
    private int height;
    private Camera sceneCamera;
    private final Camera multiViewRenderCamera = new Camera(1, 1);
    private final Camera processingCamera = new Camera(1, 1);
    private float bottom;
    private float left;
    private float right;
    private float top;
    private int originalWidth;
    private int originalHeight;
    private int cameraWidth;
    private int cameraHeight;
    private int logicalViewWidth;
    private int logicalViewHeight;
    private boolean multiViewRenderCameraInstalled;
    private boolean resizeWithDefaultFramebuffer;
    private int lastFilterIndex = -1;
    private boolean cameraInit = false;
    private boolean multiView = false;
    private AppProfiler prof;

    private Format fbFormat = null;
    private Format depthFormat = Format.Depth;

    /**
     * Creates a new `FilterPostProcessor`.
     *
     * @param assetManager The asset manager to be used by filters for loading assets.
     */
    public FilterPostProcessor(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    /**
     * Constructs a new instance of FilterPostProcessor.
     *
     * @param assetManager The asset manager used to load resources needed by the processor.
     * @param useFullscreenTriangle If true, a fullscreen triangle will be used for rendering;
     *                              otherwise, a quad will be used.
     */
    public FilterPostProcessor(AssetManager assetManager, boolean useFullscreenTriangle) {
        this(assetManager);
        this.useFullscreenTriangle = useFullscreenTriangle;
    }

    /**
     * Serialization-only constructor. Do not use this constructor directly;
     * use {@link #FilterPostProcessor(AssetManager)}.
     */
    protected FilterPostProcessor() {
    }

    /**
     * Adds a filter to the list of filters to be applied. Filters are applied
     * in the order they are added. If the processor is already initialized,
     * the filter is immediately initialized as well.
     *
     * @param filter The filter to add (not null).
     * @throws IllegalArgumentException If the provided filter is null.
     */
    public void addFilter(Filter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Filter cannot be null.");
        }
        filters.add(filter);

        if (isInitialized()) {
            initFilter(filter, viewPort);
        }

        setFilterState(filter, filter.isEnabled());
    }

    /**
     * Removes a specific filter from the list. The filter's `cleanup` method
     * is called upon removal.
     *
     * @param filter The filter to remove (not null).
     * @throws IllegalArgumentException If the provided filter is null.
     */
    public void removeFilter(Filter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Filter cannot be null.");
        }
        filters.remove(filter);
        filter.cleanup(renderer);
        updateLastFilterIndex();
    }

    /**
     * Returns an iterator over the filters currently managed by this processor.
     *
     * @return An `Iterator` of {@link Filter} objects.
     */
    public Iterator<Filter> getFilterIterator() {
        return filters.iterator();
    }

    /**
     * Initializes the `FilterPostProcessor`. This method is called by the
     * `RenderManager` when the processor is added to a viewport.
     *
     * @param rm The `RenderManager` instance.
     * @param vp The `ViewPort` this processor is attached to.
     */
    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        renderManager = rm;
        renderer = rm.getRenderer();
        viewPort = vp;
        if(useFullscreenTriangle) {
            fsQuad = new Geometry("FsQuad", new FullscreenTriangle());
        }else{
            Picture fullscreenQuad = new Picture("filter full screen quad");
            fullscreenQuad.setWidth(1);
            fullscreenQuad.setHeight(1);
            fsQuad = fullscreenQuad;
        }

        // Determine optimal framebuffer format based on renderer capabilities
        if (fbFormat == null) {
            fbFormat = renderer.getBestColorTargetFormat(true, false, false);
        }

        Camera cam = vp.getCamera();

        // Save original viewport dimensions
        left = cam.getViewPortLeft();
        right = cam.getViewPortRight();
        top = cam.getViewPortTop();
        bottom = cam.getViewPortBottom();
        originalWidth = vp.getRenderTargetWidth();
        originalHeight = vp.getRenderTargetHeight();

        // First call to reshape to set up internal framebuffers and textures
        reshape(vp, originalWidth, originalHeight);
    }

    /**
     * Returns the default color buffer format used for the internal rendering passes of the filters. This
     * format is determined during initialization based on the renderer's capabilities.
     *
     * @return The default `Format` for the filter pass textures or null if set to be determined
     *         automatically.
     */
    public Format getDefaultPassTextureFormat() {
        return fbFormat;
    }

    /**
     * Initializes a single filter. This method is called when a filter is added
     * or when the post-processor is initialized/reshaped. It sets the processor
     * for the filter, handles depth texture requirements, and calls the filter's
     * `init` method.
     *
     * @param filter The {@link Filter} to initialize.
     * @param vp The `ViewPort` associated with this processor.
     */
    private void initFilter(Filter filter, ViewPort vp) {
        filter.setProcessor(this);
        if (filter.isRequiresDepthTexture()) {
            if (!computeDepth && renderFrameBuffer != null) {
                // If depth texture is required and not yet created, create it
                depthTexture = new Texture2D(width, height, depthFormat);
                renderFrameBuffer.setDepthTarget(FrameBufferTarget.newTarget(depthTexture));
            }
            computeDepth = true; // Mark that depth texture is needed
            filter.init(assetManager, renderManager, vp, width, height);
            filter.setDepthTexture(depthTexture);
        } else {
            filter.init(assetManager, renderManager, vp, width, height);
        }
    }

    /**
     * Renders a filter's material onto a full-screen quad. This method
     * handles setting up the rendering context (framebuffer, camera, material)
     * for a filter pass. It copies the scene camera into a dedicated processing
     * camera, then adjusts only that copy for the target framebuffer.
     *
     * @param r The `Renderer` instance.
     * @param buff The `FrameBuffer` to render to.
     * @param mat The `Material` to use for rendering the filter.
     */
    private void renderProcessing(Renderer r, FrameBuffer buff, Material mat) {
        // Adjust camera and viewport based on target framebuffer
        if (buff == outputBuffer) {
            Camera camera = prepareProcessingCamera(cameraWidth, cameraHeight, left, right, bottom, top);
            renderManager.setCamera(camera, false, originalWidth, originalHeight);
            // Disable depth test/write for final pass to prevent artifacts
            if (mat.getAdditionalRenderState().isDepthWrite()) {
                mat.getAdditionalRenderState().setDepthTest(false);
                mat.getAdditionalRenderState().setDepthWrite(false);
            }
        } else {
            // Rendering to an intermediate framebuffer for a filter pass
            Camera camera = prepareProcessingCamera(logicalViewWidth, logicalViewHeight, 0f, 1f, 0f, 1f);
            renderManager.setCamera(camera, false, buff.getWidth(), buff.getHeight());
            // Enable depth test/write for intermediate passes if material needs it
            mat.getAdditionalRenderState().setDepthTest(true);
            mat.getAdditionalRenderState().setDepthWrite(true);
        }

        fsQuad.setMaterial(mat);
        fsQuad.updateGeometricState();

        r.setFrameBuffer(buff);
        r.clearBuffers(true, true, true); // Clear color, depth, and stencil buffers
        renderManager.renderGeometry(fsQuad);
    }

    /**
     * Checks if the `FilterPostProcessor` has been initialized.
     *
     * @return True if initialized, false otherwise.
     */
    @Override
    public boolean isInitialized() {
        return viewPort != null;
    }

    @Override
    public void postQueue(RenderQueue rq) {
        for (Filter filter : filters.getArray()) {
            if (filter.isEnabled()) {
                if (prof != null) {
                    prof.spStep(SpStep.ProcPostQueue, FPP, filter.getName());
                }
                filter.postQueue(rq);
            }
        }
    }

    @Override
    public void appendPostQueuePasses(FrameGraph graph, RenderQueue queue) {
        filterGraph = graph;
        int index = 0;
        for (Filter filter : filters.getArray()) {
            graph.addExpansionPass("Filter[" + index++ + "]:" + filter.getName(), current -> {
                if (filter.isEnabled()) {
                    if (prof != null) {
                        prof.spStep(SpStep.ProcPostQueue, FPP, filter.getName());
                    }
                    filter.appendPostQueuePasses(current, queue);
                }
            });
        }
    }

    /**
     * Renders the chain of filters. This method is the core of the post-processing.
     * It iterates through each enabled filter, handling pre-filter passes,
     * setting up textures (scene, depth), performing the main filter rendering,
     * and managing intermediate framebuffers.
     *
     * @param r The `Renderer` instance.
     * @param sceneFb The framebuffer containing the rendered scene (either MS or single-sample).
     */
    private void appendFilterChain(FrameGraph graph, Renderer r, FrameBuffer sceneFb) {
        for (Filter filter : filters.getArray()) {
            if (filter.isEnabled() && filter instanceof FrameGraphFilter) {
                appendMixedFilterChain(graph, r, sceneFb);
                return;
            }
        }
        FilterChainState chain = new FilterChainState();
        chain.texture = filterTexture;
        chain.buffer = sceneFb;
        ResourceHandle<FrameBuffer> scene = graph.importResource(
                "Scene", FrameBuffer.class, () -> sceneFb);
        FrameGraph.PassBuilder previous = null;
        for (int i = 0; i < filters.size(); i++) {
            final int index = i;
            Filter filter = filters.get(i);
            if (!filter.isEnabled()) {
                continue;
            }
            if (filter.getPostRenderPasses() != null) {
                int passIndex = 0;
                for (Filter.Pass filterPass : filter.getPostRenderPasses()) {
                    String passName = filterPass.name == null ? "Pass" : filterPass.name;
                    String name = "Filter[" + i + "]:" + filter.getName() + "/" + passIndex++ + ":" + passName;
                    ResourceHandle<FrameBuffer> passTarget = graph.importResource(
                            name, FrameBuffer.class, filterPass::getRenderFrameBuffer);
                    FrameGraph.PassBuilder subpass = graph.addPass(name, context -> runGraphPass(() -> {
                        context.get(scene);
                        context.get(passTarget);
                        renderFilterPass(r, chain, filter, filterPass);
                    })).read(scene).write(passTarget).sideEffect();
                    if (previous != null) {
                        subpass.after(previous);
                    }
                    previous = subpass;
                }
            }
            // Borrow actual Filter-owned targets; preserve callback order even for custom filters.
            ResourceHandle<FrameBuffer> output = graph.importResource(
                    "FilterOutput" + i, FrameBuffer.class,
                    () -> index == lastFilterIndex ? outputBuffer : filter.getRenderFrameBuffer());
            FrameGraph.PassBuilder pass = graph.addPass(
                    "Filter[" + i + "]:" + filter.getName(), context -> runGraphPass(() -> {
                        context.get(scene);
                        context.get(output);
                        renderFilter(r, sceneFb, chain, filter, index);
                    })).read(scene).write(output).sideEffect();
            if (previous != null) {
                pass.after(previous);
            }
            previous = pass;
        }
    }

    private void appendMixedFilterChain(FrameGraph graph, Renderer renderer, FrameBuffer sceneFb) {
        ResourceHandle<RenderTarget> color = graph.importResource("SceneColor", RenderTarget.class,
            () -> RenderTarget.borrowed(sceneFb, filterTexture, depthTexture));
        ResourceHandle<Texture2D> depth = graph.importResource("OriginalSceneDepth", Texture2D.class,
            () -> depthTexture);
        boolean graphOutput = false;
        FilterChainState chain = new FilterChainState();
        for (int i = 0; i < filters.size(); i++) {
            Filter filter = filters.get(i);
            if (!filter.isEnabled()) {
                continue;
            }
            String prefix = "Filter[" + i + "]:" + filter.getName();
            ResourceHandle<RenderTarget> input = color;
            if (filter instanceof FrameGraphFilter) {
                FilterGraphContext builder = new FilterGraphContext(graph, this, prefix, width, height, depth);
                color = java.util.Objects.requireNonNull(
                    ((FrameGraphFilter) filter).build(builder, input), "Graph filter returned null");
                graphOutput = true;
                continue;
            }

            // Legacy adapters declare native input reads so its pooled allocation
            // cannot be reused before the legacy filter has finished sampling it.
            if (filter.getPostRenderPasses() != null) {
                int passIndex = 0;
                for (Filter.Pass filterPass : filter.getPostRenderPasses()) {
                    String passName = filterPass.name == null ? "Pass" : filterPass.name;
                    String name = prefix + "/" + passIndex++ + ":" + passName;
                    ResourceHandle<FrameBuffer> target = graph.importResource(name, FrameBuffer.class,
                        filterPass::getRenderFrameBuffer);
                    graph.addPass(name, context -> runGraphPass(() -> {
                        RenderTarget source = context.get(input);
                        chain.texture = source.getColorTexture(0);
                        chain.buffer = source.getFrameBuffer();
                        context.get(target);
                        renderFilterPass(renderer, chain, filter, filterPass);
                    })).read(input).read(depth).write(target);
                }
            }
            final int index = i;
            if (i != lastFilterIndex) {
                ResourceHandle<RenderTarget> output = graph.importResource(prefix + "/Color",
                    RenderTarget.class, () -> RenderTarget.borrowed(
                        filter.getRenderFrameBuffer(), filter.getRenderedTexture(), null));
                graph.addPass(prefix, context -> runGraphPass(() -> {
                    RenderTarget source = context.get(input);
                    chain.texture = source.getColorTexture(0);
                    chain.buffer = source.getFrameBuffer();
                    context.get(output);
                    renderFilter(renderer, sceneFb, chain, filter, index);
                })).read(input).read(depth).write(output);
                color = output;
            } else {
                ResourceHandle<FrameBuffer> output = graph.importResource(prefix + "/Output",
                    FrameBuffer.class, () -> outputBuffer);
                graph.addPass(prefix, context -> runGraphPass(() -> {
                    RenderTarget source = context.get(input);
                    chain.texture = source.getColorTexture(0);
                    chain.buffer = source.getFrameBuffer();
                    context.get(output);
                    renderFilter(renderer, sceneFb, chain, filter, index);
                })).read(input).read(depth).write(output);
            }
            graphOutput = false;
        }
        if (graphOutput) {
            if (graphCopyMaterial == null) {
                graphCopyMaterial = new Material(assetManager, "Common/MatDefs/Post/FrameGraphCopy.j3md");
            }
            ResourceHandle<RenderTarget> result = color;
            ResourceHandle<FrameBuffer> output = graph.importResource("GraphFilterOutput",
                FrameBuffer.class, () -> outputBuffer);
            graph.addPass("GraphFilters/Present", context -> runGraphPass(() -> {
                drawGraphMaterial(graphCopyMaterial, context.get(result).getColorTexture(0),
                    context.get(depth), context.get(output));
            })).read(result).read(depth).write(output);
        }
    }

    void drawGraphMaterial(Material material, Texture2D input, Texture2D depth, FrameBuffer output) {
        if (material.getMaterialDef().getMaterialParam("Texture") == null) {
            throw new IllegalArgumentException("Graph material must declare Texture");
        }
        material.setTexture("Texture", input);
        setGraphSamples(material, "NumSamples", input);
        if (material.getMaterialDef().getMaterialParam("DepthTexture") != null) {
            if (depth == null) {
                throw new IllegalStateException("Override isRequiresDepthTexture() to request scene depth");
            }
            material.setTexture("DepthTexture", depth);
            setGraphSamples(material, "NumSamplesDepth", depth);
        }
        // A half-resolution pass must not change the allocation basis for the next pass.
        Camera camera = viewPort.getCamera();
        int oldWidth = camera.getWidth();
        int oldHeight = camera.getHeight();
        float oldLeft = camera.getViewPortLeft();
        float oldRight = camera.getViewPortRight();
        float oldBottom = camera.getViewPortBottom();
        float oldTop = camera.getViewPortTop();
        try {
            renderProcessing(renderer, output, material);
        } finally {
            if (output != outputBuffer) {
                camera.resize(oldWidth, oldHeight, false);
                camera.setViewPort(oldLeft, oldRight, oldBottom, oldTop);
                renderManager.setCamera(camera, false);
            }
        }
    }

    private void setGraphSamples(Material material, String parameter, Texture2D texture) {
        int samples = texture.getImage().getMultiSamples();
        if (material.getMaterialDef().getMaterialParam(parameter) != null) {
            if (samples > 1) {
                material.setInt(parameter, samples);
            } else {
                material.clearParam(parameter);
            }
        } else if (samples > 1) {
            throw new IllegalArgumentException("Multisampled input requires " + parameter);
        }
    }

    private void renderFilterPass(Renderer r, FilterChainState chain, Filter filter, Filter.Pass pass) {
        if (!filter.isEnabled()) {
            return;
        }
        boolean msDepth = depthTexture != null && depthTexture.getImage().getMultiSamples() > 1;
        if (prof != null) {
            prof.spStep(SpStep.ProcPostFrame, FPP, filter.getName(), pass.toString());
        }
        pass.beforeRender();

        // Set scene texture if required by the pass.
        if (pass.requiresSceneAsTexture()) {
            pass.getPassMaterial().setTexture("Texture", chain.texture);
            if (chain.texture.getImage().getMultiSamples() > 1) {
                pass.getPassMaterial().setInt("NumSamples", chain.texture.getImage().getMultiSamples());
            } else {
                pass.getPassMaterial().clearParam("NumSamples");
            }
        }

        // Set depth texture if required by the pass.
        if (pass.requiresDepthAsTexture()) {
            pass.getPassMaterial().setTexture("DepthTexture", depthTexture);
            if (msDepth) {
                pass.getPassMaterial().setInt("NumSamplesDepth", depthTexture.getImage().getMultiSamples());
            } else {
                pass.getPassMaterial().clearParam("NumSamplesDepth");
            }
        }
        renderProcessing(r, pass.getRenderFrameBuffer(), pass.getPassMaterial());
    }

    private void renderFilter(Renderer r, FrameBuffer sceneFb,
            FilterChainState chain, Filter filter, int i) {
        boolean msDepth = depthTexture != null && depthTexture.getImage().getMultiSamples() > 1;
        if (prof != null) {
            prof.spStep(SpStep.ProcPostFrame, FPP, filter.getName());
        }
        if (filter.isEnabled()) {
            if (prof != null) {
                prof.spStep(SpStep.ProcPostFrame, FPP, filter.getName(), "postFrame");
            }
            filter.postFrame(renderManager, viewPort, chain.buffer, sceneFb);

            Material mat = filter.getMaterial();
            if (msDepth && filter.isRequiresDepthTexture()) {
                mat.setInt("NumSamplesDepth", depthTexture.getImage().getMultiSamples());
            }
            if (filter.isRequiresSceneTexture()) {
                mat.setTexture("Texture", chain.texture);
                if (chain.texture.getImage().getMultiSamples() > 1) {
                    mat.setInt("NumSamples", chain.texture.getImage().getMultiSamples());
                } else {
                    mat.clearParam("NumSamples");
                }
            }

            Texture sampledTexture = chain.texture;
            Texture.MagFilter previousMag = sampledTexture.getMagFilter();
            Texture.MinFilter previousMin = sampledTexture.getMinFilter();
            boolean wantsBilinear = filter.isRequiresBilinear();
            if (wantsBilinear) {
                chain.texture.setMagFilter(Texture.MagFilter.Bilinear);
                chain.texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            }

            chain.buffer = outputBuffer;
            if (i != lastFilterIndex) {
                chain.buffer = filter.getRenderFrameBuffer();
                chain.texture = filter.getRenderedTexture();
            }
            if (prof != null) {
                prof.spStep(SpStep.ProcPostFrame, FPP, filter.getName(), "render");
            }
            try {
                renderProcessing(r, chain.buffer, mat);
                if (prof != null) {
                    prof.spStep(SpStep.ProcPostFrame, FPP, filter.getName(), "postFilter");
                }
                filter.postFilter(r, chain.buffer);
            } finally {
                // Restore the sampled input, which may belong to the graph's reusable pool.
                if (wantsBilinear) {
                    sampledTexture.setMagFilter(previousMag);
                    sampledTexture.setMinFilter(previousMin);
                }
            }
        }
    }

    @Override
    public void postFrame(FrameBuffer out) {
        // Direct legacy invocation only; RenderManager appends to its own graph.
        try (FrameGraph graph = new FrameGraph()) {
            graph.addExpansionPass("Post", current -> appendPostFramePasses(current, viewPort));
            graph.execute(renderManager, viewPort, viewPort.getQueue());
        } finally {
            restoreOutput();
            filterGraph = null;
        }
    }

    @Override
    public void appendPostFramePasses(FrameGraph graph, ViewPort viewport) {
        filterGraph = graph;
        FrameBuffer sceneBuffer = renderFrameBuffer;
        if (renderFrameBufferMS != null && !renderer.getCaps().contains(Caps.OpenGL32)) {
            graph.addPass("ResolveMSAA", context -> runGraphPass(() -> {
                renderer.copyFrameBuffer(renderFrameBufferMS, renderFrameBuffer, true, true);
            })).sideEffect();
        } else if (renderFrameBufferMS != null) {
            sceneBuffer = renderFrameBufferMS;
        }
        appendFilterChain(graph, renderer, sceneBuffer);
        graph.addPass("RestoreOutput", context -> runGraphPass(() -> {
            restoreOutput();
        })).sideEffect();
    }

    @Override
    public void preFrame(float tpf) {
        if (filters.isEmpty() || lastFilterIndex == -1) {
            // If no filters are enabled, restore the output framebuffer and
            // bypass the post-processor.
            if (cameraInit) {
                restoreSceneCamera();
                viewPort.setOutputFrameBuffer(outputBuffer);
                cameraInit = false;
            }
        } else {
            setupViewPortFrameBuffer();
            // A multi-view FPP owns a cropped physical framebuffer. Render the
            // scene through a logical-sized camera whose viewport covers that
            // whole framebuffer, without modifying the original camera object.
            if (multiView) {
                installMultiViewRenderCamera();
            } else {
                restoreSceneCamera();
            }
        }

        // Call preFrame on all enabled filters
        for (Filter filter : filters.getArray()) {
            if (filter.isEnabled()) {
                if (prof != null) {
                    prof.spStep(SpStep.ProcPreFrame, FPP, filter.getName());
                }
                filter.preFrame(tpf);
            }
        }
    }

    /**
     * Sets the enabled state of a specific filter. If the filter is part of
     * this processor's list, its `enabled` flag is updated, and the
     * `lastFilterIndex` is recomputed.
     *
     * @param filter The {@link Filter} to modify (not null).
     * @param enabled True to enable the filter, false to disable it.
     */
    protected void setFilterState(Filter filter, boolean enabled) {
        if (filters.contains(filter)) {
            filter.enabled = enabled;
            updateLastFilterIndex();
        }
    }

    /**
     * Computes the index of the last enabled filter in the list. This is used
     * to determine which filter should render to the final output framebuffer
     * and which should render to intermediate framebuffers. If no filters are
     * enabled, the viewport's output framebuffer is restored to its original.
     */
    private void updateLastFilterIndex() {
        lastFilterIndex = -1;
        for (int i = filters.size() - 1; i >= 0 && lastFilterIndex == -1; i--) {
            if (filters.get(i).isEnabled()) {
                lastFilterIndex = i;
                // If the FPP is initialized but the viewport framebuffer is the
                // original output framebuffer (meaning no filter was enabled
                // previously), then redirect it to the FPP's internal framebuffer.
                if (isInitialized() && viewPort.getOutputFrameBuffer() == outputBuffer) {
                    setupViewPortFrameBuffer();
                }
                return;
            }
        }
        // If no filters are enabled, restore the original framebuffer to the viewport.
        if (isInitialized() && lastFilterIndex == -1) {
            viewPort.setOutputFrameBuffer(outputBuffer);
        }
    }

    @Override
    public void cleanup() {
        filterGraph = null;
        if (viewPort != null) {
            // Reset FPP render state and restore the original framebuffer.
            restoreSceneCamera();
            viewPort.setOutputFrameBuffer(outputBuffer);
            viewPort = null;

            cleanupFilterResources();
            cleanupFrameBuffers();
        }
    }

    /**
     * Sets the profiler instance for this processor.
     *
     * @param profiler The `AppProfiler` instance to use for performance monitoring.
     */
    @Override
    public void setProfiler(AppProfiler profiler) {
        this.prof = profiler;
    }

    /**
     * Reshapes the `FilterPostProcessor` when the viewport or canvas size changes.
     * This method recalculates internal framebuffer dimensions, creates new
     * framebuffers and textures if necessary (e.g., for anti-aliasing), and
     * reinitializes all filters with the new dimensions. It also detects
     * multi-view scenarios.
     *
     * @param vp The `ViewPort` being reshaped.
     * @param w The new width of the viewport's canvas.
     * @param h The new height of the viewport's canvas.
     */
    @Override
    public void reshape(ViewPort vp, int w, int h) {
        restoreSceneCamera();
        Camera cam = vp.getCamera();
        cameraWidth = cam.getWidth();
        cameraHeight = cam.getHeight();
        left = cam.getViewPortLeft();
        right = cam.getViewPortRight();
        top = cam.getViewPortTop();
        bottom = cam.getViewPortBottom();
        originalWidth = w;
        originalHeight = h;

        // Computing real dimension of the viewport based on its relative size within the canvas
        width = (int) (w * (Math.abs(right - left)));
        height = (int) (h * (Math.abs(bottom - top)));
        width = Math.max(1, width);
        height = Math.max(1, height);
        logicalViewWidth = Math.max(1, (int) (cameraWidth * Math.abs(right - left)));
        logicalViewHeight = Math.max(1, (int) (cameraHeight * Math.abs(top - bottom)));

        // Test if original dimensions differ from actual viewport dimensions.
        // If they are different, we are in a multiview situation, and the
        // camera must be handled differently (e.g., resized to the sub-viewport).
        multiView = originalWidth != width || originalHeight != height;

        cameraInit = true;
        computeDepth = false;

        boolean initialized = renderFrameBuffer != null || renderFrameBufferMS != null;
        if (!initialized) {
            outputBuffer = viewPort.getOutputFrameBuffer();
            resizeWithDefaultFramebuffer = outputBuffer == null;
        } else {
            viewPort.setOutputFrameBuffer(outputBuffer);
            cleanupFilterResources();
            cleanupFrameBuffers();
        }

        Collection<Caps> caps = renderer.getCaps();

        // antialiasing on filters only supported in opengl 3 due to depth read problem
        if (numSamples > 1 && caps.contains(Caps.FrameBufferMultisample)) {
            renderFrameBufferMS = new FrameBuffer(width, height, numSamples);

            // If OpenGL 3.2+ is supported, multisampled textures can be attached directly
            if (caps.contains(Caps.OpenGL32)) {
                Texture2D msColor = new Texture2D(width, height, numSamples, fbFormat);
                Texture2D msDepth = new Texture2D(width, height, numSamples, depthFormat);
                renderFrameBufferMS.setDepthTarget(FrameBufferTarget.newTarget(msDepth));
                renderFrameBufferMS.addColorTarget(FrameBufferTarget.newTarget(msColor));
                filterTexture = msColor;
                depthTexture = msDepth;
            } else {
                // Otherwise, multisampled framebuffer must use internal texture, which cannot be directly read
                renderFrameBufferMS.setDepthTarget(FrameBufferTarget.newTarget(depthFormat));
                renderFrameBufferMS.addColorTarget(FrameBufferTarget.newTarget(fbFormat));
            }
        }

        // Setup single-sampled framebuffer if no multisampling, or if OpenGL 3.2+ is not supported
        // (because for non-GL32, a single-sampled buffer is still needed to copy MS content into).
        if (numSamples <= 1 || !caps.contains(Caps.OpenGL32) || !caps.contains(Caps.FrameBufferMultisample)) {
            renderFrameBuffer = new FrameBuffer(width, height, 1);
            renderFrameBuffer.setDepthTarget(FrameBufferTarget.newTarget(depthFormat));
            filterTexture = new Texture2D(width, height, fbFormat);
            renderFrameBuffer.addColorTarget(FrameBufferTarget.newTarget(filterTexture));
        }

        // Set names for debugging
        if (renderFrameBufferMS != null) {
            renderFrameBufferMS.setName("FilterPostProcessor MS");
        }

        if (renderFrameBuffer != null) {
            renderFrameBuffer.setName("FilterPostProcessor");
        }

        // Initialize all existing filters with the new dimensions
        for (Filter filter : filters.getArray()) {
            initFilter(filter, vp);
        }
        setupViewPortFrameBuffer();
        // Flags may change before filters receive their processor during init.
        updateLastFilterIndex();
    }

    private void installMultiViewRenderCamera() {
        restoreSceneCamera();
        sceneCamera = viewPort.getCamera();
        multiViewRenderCamera.copyFrom(sceneCamera);
        multiViewRenderCamera.resize(logicalViewWidth, logicalViewHeight, false);
        multiViewRenderCamera.setViewPort(0f, 1f, 0f, 1f);
        viewPort.setCamera(multiViewRenderCamera);
        multiViewRenderCameraInstalled = true;
    }

    Camera prepareProcessingCamera(int logicalWidth, int logicalHeight,
            float viewportLeft, float viewportRight, float viewportBottom, float viewportTop) {
        Camera sourceCamera = multiViewRenderCameraInstalled ? sceneCamera : viewPort.getCamera();
        processingCamera.copyFrom(sourceCamera);
        processingCamera.resize(Math.max(logicalWidth, 1), Math.max(logicalHeight, 1), false);
        processingCamera.setViewPort(viewportLeft, viewportRight, viewportBottom, viewportTop);
        return processingCamera;
    }

    private void runGraphPass(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            restoreOutput();
            throw failure;
        }
    }

    private void restoreOutput() {
        renderer.setFrameBuffer(outputBuffer);
        restoreSceneCamera();
        if (viewPort != null) {
            renderManager.setCamera(viewPort.getCamera(), false, originalWidth, originalHeight);
        }
    }

    private void restoreSceneCamera() {
        if (!multiViewRenderCameraInstalled) {
            return;
        }
        if (viewPort != null && viewPort.getCamera() == multiViewRenderCamera) {
            viewPort.setCamera(sceneCamera);
        }
        multiViewRenderCameraInstalled = false;
    }

    private void cleanupFilterResources() {
        for (Filter filter : filters.getArray()) {
            filter.cleanup(renderer);
        }
    }

    private void cleanupFrameBuffers() {
        if (renderFrameBuffer != null) {
            renderFrameBuffer.dispose();
            renderFrameBuffer = null;
        }
        if (renderFrameBufferMS != null) {
            renderFrameBufferMS.dispose();
            renderFrameBufferMS = null;
        }
        if (filterTexture != null && filterTexture.getImage() != null) {
            filterTexture.getImage().dispose();
        }
        filterTexture = null;
        if (depthTexture != null && depthTexture.getImage() != null) {
            depthTexture.getImage().dispose();
        }
        depthTexture = null;
    }

    /**
     * Returns the number of samples used for anti-aliasing.
     *
     * @return The number of samples.
     */
    public int getNumSamples() {
        return numSamples;
    }

    /**
     * Removes all filters currently added to this processor.
     */
    public void removeAllFilters() {
        filters.clear();
        updateLastFilterIndex();
    }

    /**
     * Sets the number of samples for anti-aliasing. A value of 1 means no
     * anti-aliasing. This method should generally be called before the
     * processor is initialized to have an effect.
     *
     * @param numSamples The number of samples. Must be greater than 0.
     * @throws IllegalArgumentException If `numSamples` is less than or equal to 0.
     */
    public void setNumSamples(int numSamples) {
        if (numSamples <= 0) {
            throw new IllegalArgumentException("numSamples must be > 0");
        }

        this.numSamples = numSamples;
    }

    /**
     * Sets the asset manager for this processor
     *
     * @param assetManager to load assets
     */
    public void setAssetManager(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    /**
     * Sets the preferred `Image.Format` to be used for the internal frame buffer's color buffer.
     *
     * @param fbFormat
     *            The desired `Format` for the color buffer or null to let the processor determine the optimal
     *            format based on renderer capabilities.
     */
    public void setFrameBufferFormat(Format fbFormat) {
        this.fbFormat = fbFormat;
    }

    /**
     * Sets the preferred `Image.Format` to be used for the internal frame buffer's
     * depth buffer.
     *
     * @param depthFormat The desired `Format` for the depth buffer.
     */
    public void setFrameBufferDepthFormat(Format depthFormat) {
        this.depthFormat = depthFormat;
    }

    /**
     * Returns the `Image.Format` currently used for the internal frame buffer's
     * depth buffer.
     *
     * @return The current depth `Format`.
     */
    public Format getFrameBufferDepthFormat() {
        return depthFormat;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(JmeExporter ex) throws IOException {
        OutputCapsule oc = ex.getCapsule(this);
        oc.write(numSamples, "numSamples", 0);
        oc.write(useFullscreenTriangle, "useFullscreenTriangle", false);
        oc.writeSavableArrayList(new ArrayList(filters), "filters", null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void read(JmeImporter im) throws IOException {
        InputCapsule ic = im.getCapsule(this);
        numSamples = ic.readInt("numSamples", 0);
        useFullscreenTriangle = ic.readBoolean("useFullscreenTriangle", false);
        filters = new SafeArrayList<>(Filter.class, ic.readSavableArrayList("filters", null));
        for (Filter filter : filters.getArray()) {
            filter.setProcessor(this);
            setFilterState(filter, filter.isEnabled());
        }
        assetManager = im.getAssetManager();
    }

    /**
     * For internal use only.
     * Returns the depth texture generated from the scene's depth buffer.
     * This texture is available if any filter requires a depth texture.
     *
     * @return The `Texture2D` containing the scene's depth information, or null if not computed.
     */
    public Texture2D getDepthTexture() {
        return depthTexture;
    }

    /**
     * For internal use only.
     * Returns the color texture that contains the rendered scene or the output
     * of the previous filter in the chain. This texture serves as input for subsequent filters.
     *
     * @return The `Texture2D` containing the scene's color information or the intermediate filter output.
     */
    public Texture2D getFilterTexture() {
        return filterTexture;
    }

    /**
     * Returns the first filter in the managed list that is assignable from the
     * given filter type. Useful for retrieving specific filters to modify their properties.
     *
     * @param <T> The type of the filter to retrieve.
     * @param filterType The `Class` object representing the filter type.
     * @return A filter instance assignable from `filterType`, or null if no such filter is found.
     */
    @SuppressWarnings("unchecked")
    public <T extends Filter> T getFilter(Class<T> filterType) {
        for (Filter f : filters.getArray()) {
            if (filterType.isAssignableFrom(f.getClass())) {
                return (T) f;
            }
        }
        return null;
    }

    /**
     * Returns an unmodifiable version of the list of filters currently
     * managed by this processor.
     *
     * @return An unmodifiable `List` of {@link Filter} objects.
     */
    public List<Filter> getFilterList(){
        return Collections.unmodifiableList(filters);
    }

    private void setupViewPortFrameBuffer() {
        if (renderFrameBufferMS != null) {
            viewPort.setOutputFrameBuffer(renderFrameBufferMS);
        } else {
            viewPort.setOutputFrameBuffer(renderFrameBuffer);
        }
        viewPort.setResizeWithDefaultFramebuffer(resizeWithDefaultFramebuffer);
    }
}

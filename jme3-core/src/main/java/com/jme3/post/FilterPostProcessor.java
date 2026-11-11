/*
 * Copyright (c) 2009-2020 jMonkeyEngine
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
import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.profile.*;
import com.jme3.renderer.*;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
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
 * A FilterPostProcessor is a processor that can apply several {@link Filter}s to a rendered scene<br>
 * It manages a list of filters that will be applied in the order in which they've been added to the list
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
    private Picture fsQuad;
    private boolean computeDepth = false;
    private FrameBuffer outputBuffer;
    private int width;
    private int height;
    private float bottom;
    private float left;
    private float right;
    private float top;
    private int originalWidth;
    private int originalHeight;
    private int lastFilterIndex = -1;
    private boolean cameraInit = false;
    private boolean multiView = false;
    private AppProfiler prof;

    private Format fbFormat = Format.RGB111110F;
    private Format depthFormat = Format.Depth;

    /**
     * Create a FilterProcessor
     * @param assetManager the assetManager
     */
    public FilterPostProcessor(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    /**
     * Don't use this constructor, use {@link #FilterPostProcessor(AssetManager assetManager)}<br>
     * This constructor is used for serialization only
     */
    protected FilterPostProcessor() {
    }

    /**
     * Adds a filter to the filters list<br>
     * @param filter the filter to add
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
     * removes this filters from the filters list
     * @param filter
     */
    public void removeFilter(Filter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Filter cannot be null.");
        }
        filters.remove(filter);
        filter.cleanup(renderer);
        updateLastFilterIndex();
    }

    public Iterator<Filter> getFilterIterator() {
        return filters.iterator();
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        renderManager = rm;
        renderer = rm.getRenderer();
        viewPort = vp;
        fsQuad = new Picture("filter full screen quad");
        fsQuad.setWidth(1);
        fsQuad.setHeight(1);

        if (!renderer.getCaps().contains(Caps.PackedFloatTexture)) {
            if (!renderer.getCaps().contains(Caps.FloatTexture)) {
                fbFormat = Format.RGB8;
            } else {
                fbFormat = Format.RGB16F;
            }
        }

        Camera cam = vp.getCamera();

        //save view port diensions
        left = cam.getViewPortLeft();
        right = cam.getViewPortRight();
        top = cam.getViewPortTop();
        bottom = cam.getViewPortBottom();
        originalWidth = cam.getWidth();
        originalHeight = cam.getHeight();
        //first call to reshape
        reshape(vp, cam.getWidth(), cam.getHeight());
    }

    public Format getDefaultPassTextureFormat() {
        return fbFormat;
    }

    /**
     * init the given filter
     * @param filter
     * @param vp
     */
    private void initFilter(Filter filter, ViewPort vp) {
        filter.setProcessor(this);
        if (filter.isRequiresDepthTexture()) {
            if (!computeDepth && renderFrameBuffer != null) {
                depthTexture = new Texture2D(width, height, depthFormat);
                renderFrameBuffer.setDepthTexture(depthTexture);
            }
            computeDepth = true;
            filter.init(assetManager, renderManager, vp, width, height);
            filter.setDepthTexture(depthTexture);
        } else {
            filter.init(assetManager, renderManager, vp, width, height);
        }
    }

    /**
     * renders a filter on a fullscreen quad
     * @param r
     * @param buff
     * @param mat
     */
    private void renderProcessing(Renderer r, FrameBuffer buff, Material mat) {
        if (buff == outputBuffer) {
            viewPort.getCamera().resize(originalWidth, originalHeight, false);
            viewPort.getCamera().setViewPort(left, right, bottom, top);
            // update is redundant because resize and setViewPort will both
            // run the appropriate (and same) onXXXChange methods.
            // Also, update() updates some things that don't need to be updated.
            //viewPort.getCamera().update();
            renderManager.setCamera( viewPort.getCamera(), false);
            if (mat.getAdditionalRenderState().isDepthWrite()) {
                mat.getAdditionalRenderState().setDepthTest(false);
                mat.getAdditionalRenderState().setDepthWrite(false);
            }
        }else{
            viewPort.getCamera().resize(buff.getWidth(), buff.getHeight(), false);
            viewPort.getCamera().setViewPort(0, 1, 0, 1);
            // update is redundant because resize and setViewPort will both
            // run the appropriate (and same) onXXXChange methods.
            // Also, update() updates some things that don't need to be updated.
            //viewPort.getCamera().update();
            renderManager.setCamera( viewPort.getCamera(), false);
            mat.getAdditionalRenderState().setDepthTest(true);
            mat.getAdditionalRenderState().setDepthWrite(true);
        }


        fsQuad.setMaterial(mat);
        fsQuad.updateGeometricState();

        r.setFrameBuffer(buff);
        r.clearBuffers(true, true, true);
        renderManager.renderGeometry(fsQuad);
    }

    @Override
    public boolean isInitialized() {
        return viewPort != null;
    }

    @Override
    public void postQueue(RenderQueue rq) {
        for (Filter filter : filters.getArray()) {
            if (filter.isEnabled()) {
                if (prof != null) prof.spStep(SpStep.ProcPostQueue, FPP, filter.getName());
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
     * iterate through the filter list and renders filters
     * @param r
     * @param sceneFb
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
                    FrameGraph.PassBuilder subpass = graph.addPass(name, context -> {
                        context.get(scene);
                        context.get(passTarget);
                        renderFilterPass(r, chain, filter, filterPass);
                    }).read(scene).write(passTarget).sideEffect();
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
                    "Filter[" + i + "]:" + filter.getName(), context -> {
                        context.get(scene);
                        context.get(output);
                        renderFilter(r, sceneFb, chain, filter, index);
                    }).read(scene).write(output).sideEffect();
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
                    graph.addPass(name, context -> {
                        RenderTarget source = context.get(input);
                        chain.texture = source.getColorTexture(0);
                        chain.buffer = source.getFrameBuffer();
                        context.get(target);
                        renderFilterPass(renderer, chain, filter, filterPass);
                    }).read(input).read(depth).write(target);
                }
            }
            final int index = i;
            if (i != lastFilterIndex) {
                ResourceHandle<RenderTarget> output = graph.importResource(prefix + "/Color",
                    RenderTarget.class, () -> RenderTarget.borrowed(
                        filter.getRenderFrameBuffer(), filter.getRenderedTexture(), null));
                graph.addPass(prefix, context -> {
                    RenderTarget source = context.get(input);
                    chain.texture = source.getColorTexture(0);
                    chain.buffer = source.getFrameBuffer();
                    context.get(output);
                    renderFilter(renderer, sceneFb, chain, filter, index);
                }).read(input).read(depth).write(output);
                color = output;
            } else {
                ResourceHandle<FrameBuffer> output = graph.importResource(prefix + "/Output",
                    FrameBuffer.class, () -> outputBuffer);
                graph.addPass(prefix, context -> {
                    RenderTarget source = context.get(input);
                    chain.texture = source.getColorTexture(0);
                    chain.buffer = source.getFrameBuffer();
                    context.get(output);
                    renderFilter(renderer, sceneFb, chain, filter, index);
                }).read(input).read(depth).write(output);
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
            graph.addPass("GraphFilters/Present", context -> {
                drawGraphMaterial(graphCopyMaterial, context.get(result).getColorTexture(0),
                    context.get(depth), context.get(output));
            }).read(result).read(depth).write(output);
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
        if (pass.requiresSceneAsTexture()) {
            pass.getPassMaterial().setTexture("Texture", chain.texture);
            if (chain.texture.getImage().getMultiSamples() > 1) {
                pass.getPassMaterial().setInt("NumSamples", chain.texture.getImage().getMultiSamples());
            } else {
                pass.getPassMaterial().clearParam("NumSamples");

            }
        }
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
        }
        filterGraph = null;
    }

    @Override
    public void appendPostFramePasses(FrameGraph graph, ViewPort viewport) {
        filterGraph = graph;
        FrameBuffer sceneBuffer = renderFrameBuffer;
        if (renderFrameBufferMS != null && !renderer.getCaps().contains(Caps.OpenGL32)) {
            graph.addPass("ResolveMSAA", context -> {
                renderer.copyFrameBuffer(renderFrameBufferMS, renderFrameBuffer, true);
            }).sideEffect();
        } else if (renderFrameBufferMS != null) {
            sceneBuffer = renderFrameBufferMS;
        }
        appendFilterChain(graph, renderer, sceneBuffer);
        graph.addPass("RestoreOutput", context -> {
            renderer.setFrameBuffer(outputBuffer);
            if (viewPort != null) {
                renderManager.setCamera(viewPort.getCamera(), false);
            }
        }).sideEffect();
    }

    @Override
    public void preFrame(float tpf) {
        if (filters.isEmpty() || lastFilterIndex == -1) {
            //If the camera is initialized and there are no filter to render, the camera viewport is restored as it was
            if (cameraInit) {
                viewPort.getCamera().resize(originalWidth, originalHeight, true);
                viewPort.getCamera().setViewPort(left, right, bottom, top);
                viewPort.setOutputFrameBuffer(outputBuffer);
                cameraInit = false;
            }

        } else {
           setupViewPortFrameBuffer();
           //if we are ina multiview situation we need to resize the camera
           //to the viewportsize so that the backbuffer is rendered correctly
           if (multiView) {
                viewPort.getCamera().resize(width, height, false);
                viewPort.getCamera().setViewPort(0, 1, 0, 1);
                viewPort.getCamera().update();
                renderManager.setCamera(viewPort.getCamera(), false);
           }
        }

        for (Filter filter : filters.getArray()) {
            if (filter.isEnabled()) {
                if (prof != null) prof.spStep(SpStep.ProcPreFrame, FPP, filter.getName());
                filter.preFrame(tpf);
            }
        }

    }

    /**
     * sets the filter to enabled or disabled
     * @param filter
     * @param enabled
     */
    protected void setFilterState(Filter filter, boolean enabled) {
        if (filters.contains(filter)) {
            filter.enabled = enabled;
            updateLastFilterIndex();
        }
    }

    /**
     * compute the index of the last filter to render
     */
    private void updateLastFilterIndex() {
        lastFilterIndex = -1;
        for (int i = filters.size() - 1; i >= 0 && lastFilterIndex == -1; i--) {
            if (filters.get(i).isEnabled()) {
                lastFilterIndex = i;
                //the Fpp is initialized, but the viwport framebuffer is the
                //original out framebuffer so we must recover from a situation
                //where no filter was enabled. So we set the correct framebuffer
                //on the viewport
                if(isInitialized() && viewPort.getOutputFrameBuffer()==outputBuffer){
                    setupViewPortFrameBuffer();
                }
                return;
            }
        }
        if (isInitialized() && lastFilterIndex == -1) {
            //There is no enabled filter, we restore the original framebuffer
            //to the viewport to bypass the fpp.
            viewPort.setOutputFrameBuffer(outputBuffer);
        }
    }

    @Override
    public void cleanup() {
        filterGraph = null;
        if (viewPort != null) {
            //reset the viewport camera viewport to its initial value
            viewPort.getCamera().resize(originalWidth, originalHeight, true);
            viewPort.getCamera().setViewPort(left, right, bottom, top);
            viewPort.setOutputFrameBuffer(outputBuffer);
            viewPort = null;

            if(renderFrameBuffer != null){
                renderFrameBuffer.dispose();
            }
            if(depthTexture!=null){
               depthTexture.getImage().dispose();
            }
            filterTexture.getImage().dispose();
            if(renderFrameBufferMS != null){
               renderFrameBufferMS.dispose();
            }
            for (Filter filter : filters.getArray()) {
                filter.cleanup(renderer);
            }
        }

    }

    @Override
    public void setProfiler(AppProfiler profiler) {
        this.prof = profiler;
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
        Camera cam = vp.getCamera();
        //this has no effect at first init but is useful when resizing the canvas with multi views
        cam.setViewPort(left, right, bottom, top);
        //resizing the camera to fit the new viewport and saving original dimensions
        cam.resize(w, h, false);
        left = cam.getViewPortLeft();
        right = cam.getViewPortRight();
        top = cam.getViewPortTop();
        bottom = cam.getViewPortBottom();
        originalWidth = w;
        originalHeight = h;

        //computing real dimension of the viewport and resizing the camera
        width = (int) (w * (Math.abs(right - left)));
        height = (int) (h * (Math.abs(bottom - top)));
        width = Math.max(1, width);
        height = Math.max(1, height);

        //Testing original versus actual viewport dimension.
        //If they are different we are in a multiview situation and
        //camera must be handled differently
        if(originalWidth!=width || originalHeight!=height){
            multiView = true;
        }

        cameraInit = true;
        computeDepth = false;

        if (renderFrameBuffer == null && renderFrameBufferMS == null) {
            outputBuffer = viewPort.getOutputFrameBuffer();
        }

        Collection<Caps> caps = renderer.getCaps();

        //antialiasing on filters only supported in opengl 3 due to depth read problem
        if (numSamples > 1 && caps.contains(Caps.FrameBufferMultisample)) {
            renderFrameBufferMS = new FrameBuffer(width, height, numSamples);
            if (caps.contains(Caps.OpenGL32)) {
                Texture2D msColor = new Texture2D(width, height, numSamples, fbFormat);
                Texture2D msDepth = new Texture2D(width, height, numSamples, depthFormat);
                renderFrameBufferMS.setDepthTexture(msDepth);
                renderFrameBufferMS.setColorTexture(msColor);
                filterTexture = msColor;
                depthTexture = msDepth;
            } else {
                renderFrameBufferMS.setDepthBuffer(depthFormat);
                renderFrameBufferMS.setColorBuffer(fbFormat);
            }
        }

        if (numSamples <= 1 || !caps.contains(Caps.OpenGL32)) {
            renderFrameBuffer = new FrameBuffer(width, height, 1);
            renderFrameBuffer.setDepthBuffer(depthFormat);
            filterTexture = new Texture2D(width, height, fbFormat);
            renderFrameBuffer.setColorTexture(filterTexture);
        }

        for (Filter filter : filters.getArray()) {
            initFilter(filter, vp);
        }
        setupViewPortFrameBuffer();
    }

    /**
     * return the number of samples for antialiasing
     * @return numSamples
     */
    public int getNumSamples() {
        return numSamples;
    }

    /**
     *
     * Removes all the filters from this processor
     */
    public void removeAllFilters() {
        filters.clear();
        updateLastFilterIndex();
    }

    /**
     * Sets the number of samples for antialiasing
     * @param numSamples the number of Samples
     */
    public void setNumSamples(int numSamples) {
        if (numSamples <= 0) {
            throw new IllegalArgumentException("numSamples must be > 0");
        }

        this.numSamples = numSamples;
    }

    /**
     * Sets the asset manager for this processor
     * @param assetManager
     */
    public void setAssetManager(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    public void setFrameBufferFormat(Format fbFormat) {
        this.fbFormat = fbFormat;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(JmeExporter ex) throws IOException {
        OutputCapsule oc = ex.getCapsule(this);
        oc.write(numSamples, "numSamples", 0);
        oc.writeSavableArrayList(new ArrayList(filters), "filters", null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void read(JmeImporter im) throws IOException {
        InputCapsule ic = im.getCapsule(this);
        numSamples = ic.readInt("numSamples", 0);
        filters = new SafeArrayList<Filter>(Filter.class, ic.readSavableArrayList("filters", null));
        for (Filter filter : filters.getArray()) {
            filter.setProcessor(this);
            setFilterState(filter, filter.isEnabled());
        }
        assetManager = im.getAssetManager();
    }

    /**
     * For internal use only<br>
     * returns the depth texture of the scene
     * @return the depth texture
     */
    public Texture2D getDepthTexture() {
        return depthTexture;
    }

    /**
     * For internal use only<br>
     * returns the rendered texture of the scene
     * @return the filter texture
     */
    public Texture2D getFilterTexture() {
        return filterTexture;
    }

    /**
     * returns the first filter in the list assignable form the given type
     * @param <T>
     * @param filterType the filter type
     * @return a filter assignable form the given type
     */
    @SuppressWarnings("unchecked")
    public <T extends Filter> T getFilter(Class<T> filterType) {
        for (Filter c : filters.getArray()) {
            if (filterType.isAssignableFrom(c.getClass())) {
                return (T) c;
            }
        }
        return null;
    }

    /**
     * returns an unmodifiable version of the filter list.
     * @return the filters list
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
    }
    }

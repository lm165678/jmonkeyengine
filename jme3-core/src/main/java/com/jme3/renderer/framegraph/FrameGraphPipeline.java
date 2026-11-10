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
package com.jme3.renderer.framegraph;

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.queue.RenderQueue;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Installs a graph at jME's opaque Pipeline extension point:
 * <pre>{@code
 * FrameGraph graph = new FrameGraph();
 * graph.addPass("Opaque", context -> {
 *     context.getRenderQueue().renderQueue(RenderQueue.Bucket.Opaque,
 *             context.getRenderManager(), context.getViewPort().getCamera());
 * }).sideEffect();
 * renderManager.setPipeline(viewPort, new FrameGraphPipeline(graph));
 * // Restore the viewport/manager RenderPath selection:
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 * Passes consume only their rendered geometry. Transparent/sky/GUI queues and
 * existing SceneProcessors still run through RenderManager. For a graph in a
 * post-processing callback use FrameGraph.execute instead. Do not share the graph
 * or this pipeline across viewports. All operations are render-thread only.
 */
public final class FrameGraphPipeline implements RenderPipeline {
    private final FrameGraph graph;
    private final Consumer<FrameGraph> frameBuilder;
    private RenderManager manager;
    private ViewPort viewport;
    /** @param graph exclusively owned graph; built before installation */
    public FrameGraphPipeline(FrameGraph graph) {
        this.graph = Objects.requireNonNull(graph);
        this.frameBuilder = null;
    }
    /**
     * Creates a dynamic graph pipeline. Before every render, resets declarations
     * (not allocations), handles resize, invokes the builder, then compiles and
     * executes. The builder runs on the render thread and must not retain handles
     * between calls. Failures are propagated; renderer state is restored.
     * <pre>{@code
     * // 第一步：只安装一次；回调每帧执行，不必每帧 new Pipeline。
     * FrameGraphPipeline pipeline = new FrameGraphPipeline(graph -> {
     *     // 第二步：按当帧需求声明资源和 Pass；池和持久资源保留。
     *     ResourceHandle<FrameBuffer> output = graph.importResource(
     *             "Output", FrameBuffer.class, viewPort::getOutputFrameBuffer);
     *     graph.addPass("Opaque", context -> {
     *         renderer.setFrameBuffer(context.get(output));
     *         context.getRenderQueue().renderQueue(RenderQueue.Bucket.Opaque,
     *                 renderManager, viewPort.getCamera());
     *     }).write(output);
     * });
     * renderManager.setPipeline(viewPort, pipeline);
     * // 第三步：切回 Forward；旧管线的池和持久资源由引擎清理。
     * renderManager.setPipeline(viewPort, new ForwardPipeline());
     * }</pre>
     * @param frameBuilder declares the complete graph for the current frame
     */
    public FrameGraphPipeline(Consumer<FrameGraph> frameBuilder) {
        this.graph = new FrameGraph();
        this.frameBuilder = Objects.requireNonNull(frameBuilder);
    }
    /** @return graph for diagnostics; do not execute/clear it while installed */
    public FrameGraph getGraph() {
        return graph;
    }
    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        if (this.manager != null) {
            throw new IllegalStateException("Already initialized");
        }
        if (frameBuilder == null) {
            graph.compile();
        }
        this.manager = Objects.requireNonNull(manager);
        this.viewport = Objects.requireNonNull(viewport);
    }
    @Override
    public boolean isInitialized() {
        return manager != null;
    }
    @Override
    public void reshape(int width, int height) {
        graph.clearResources();
    }
    @Override
    public void render(RenderQueue queue) {
        if (manager == null) {
            throw new IllegalStateException("Not initialized");
        }
        try {
            if (frameBuilder != null) {
                graph.beginFrame(viewport.getCamera().getWidth(), viewport.getCamera().getHeight());
                frameBuilder.accept(graph);
            }
            graph.execute(manager, viewport, queue);
        } finally {
            manager.getRenderer().setFrameBuffer(viewport.getOutputFrameBuffer());
            manager.getRenderer().setBackgroundColor(viewport.getBackgroundColor());
            manager.setCamera(viewport.getCamera(), false);
        }
    }
    @Override
    public void cleanup() {
        try {
            graph.clearResources();
        } finally {
            manager = null;
            viewport = null;
        }
    }
}

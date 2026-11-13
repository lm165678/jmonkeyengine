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
package com.jme3.renderer.pipeline;

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;

/**
 * Replaceable opaque rendering strategy owned by one viewport. All calls occur
 * on the render thread with a current graphics context. RenderManager calls
 * render after auxiliary postQueue passes and then renders the remaining queues
 * normally. Implementations remove only geometry they have consumed.
 * initialize must not be called twice without cleanup; cleanup is idempotent.
 * A pipeline must restore temporary renderer overrides even when rendering fails.
 *
 * <p>Install an implementation with
 * {@code renderManager.setPipeline(viewPort, pipeline)}. Remove it with
 * {@code renderManager.setPipeline(viewPort, null)} to restore RenderPath selection.
 * The manager owns lifecycle calls; application code configures the implementation
 * rather than manually calling initialize/render/cleanup. To change configuration
 * from another thread, schedule it with {@code application.enqueue(...)}.</p>
 */
public interface OpaquePipeline {
    /**
     * Declares an entire subtree submitted independently by this pipeline. The
     * manager may omit CPU culling, render controls and queue insertion for that
     * subtree. Default false preserves legacy behavior. Return true only when
     * all descendants are owned and render-control/processor semantics are preserved.
     * Called on the render thread before scene traversal, possibly before initialize.
     * <pre>{@code
     * // 第一步：默认返回 false，沿用传统队列；普通自定义管线无需覆盖。
     * // 第二步：只有已经建立完整 GPU 提交映射、且子树没有渲染 Control 时才接管。
     * // 第三步：关闭或结构变更时立即返回 false，重建完成后才能重新接管。
     * @Override
     * public boolean handlesSceneQueue(Spatial subtree) {
     *     return initialized && enabled && !rebuilding && ownedSubtrees.contains(subtree);
     * }
     * }</pre>
     * @param subtree candidate scene subtree, borrowed
     * @return true if the complete subtree needs no traditional queue traversal
     */
    default boolean handlesSceneQueue(com.jme3.scene.Spatial subtree) {
        return false;
    }

    /**
     * Validates queue ownership before scene traversal, after processor preFrame.
     * Default no-op. Called only for initialized installed pipelines. Do not draw
     * here; invalidate stale ownership so unsupported objects enter normal queues.
     * <pre>{@code
     * // 第一步：普通管线可保留默认实现。
     * // 第二步：缓存材质/场景提交的管线在此检测变更，并撤销过期的子树接管。
     * // 第三步：实际绘制仍由 appendPasses/render 完成，不在此提交绘制。
     * }</pre>
     */
    default void prepareSceneQueue() {
    }

    /**
     * Initializes this instance for one viewport; the graphics context is current.
     * @param manager owning render manager
     * @param viewport owning viewport
     */
    void initialize(RenderManager manager, ViewPort viewport);
    /** @return whether initialization has completed and cleanup has not occurred */
    boolean isInitialized();
    /**
     * Notifies the pipeline of viewport size changes; release/recreate size-dependent
     * resources as needed. Called by the manager rather than application code.
     * @param width render-target width in physical pixels
     * @param height render-target height in physical pixels
     */
    void reshape(int width, int height);
    /**
     * Consumes supported opaque geometry after auxiliary postQueue passes. Leave
     * unsupported geometry in the queue for the original renderer. Transparent,
     * sky and GUI queues remain under RenderManager control.
     * @param queue current viewport queue, borrowed for this call
     */
    void render(RenderQueue queue);
    /**
     * Releases owned resources and borrowed references. Must tolerate repeated calls
     * and partially completed initialization. Use NativeObject.dispose for owned
     * GPU objects so the engine's resource manager handles their deletion.
     */
    void cleanup();
    /**
     * Appends opaque work to the viewport's graph. Native pipelines override this
     * to expose their individual passes; existing implementations remain compatible.
     * @param graph shared viewport graph
     * @param queue scene queue
     */
    default void appendPasses(com.jme3.renderer.framegraph.FrameGraph graph, RenderQueue queue) {
        graph.addPass("Render", context -> render(queue)).sideEffect();
    }
}

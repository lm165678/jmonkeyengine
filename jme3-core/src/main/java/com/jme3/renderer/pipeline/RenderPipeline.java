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
public interface RenderPipeline {
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
     * @param width viewport width in pixels
     * @param height viewport height in pixels
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

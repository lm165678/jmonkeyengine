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
import com.jme3.renderer.queue.RenderQueue;

/** Borrowed execution context. Never cache it or use it outside its pass callback. */
public final class FrameGraphContext {
    final FrameGraph graph;
    FrameGraph.PassBuilder pass;
    RenderManager manager;
    ViewPort viewport;
    RenderQueue queue;
    boolean aborted;
    FrameGraphContext(FrameGraph graph) {
        this.graph = graph;
    }
    private void check() {
        if (pass == null) {
            throw new IllegalStateException("No executing pass");
        }
    }
    /**
     * Resolves declared access to a logical resource. Imported resources may be
     * null (for example, the default framebuffer); owned resources never are.
     * @param handle resource declared by this pass
     * @param <T> resource type
     * @return current physical object
     */
    public <T> T get(ResourceHandle<T> handle) {
        check();
        graph.check(handle);
        if (!pass.reads.contains(handle.index) && !pass.writes.contains(handle.index)) {
            throw new IllegalStateException(pass.name + " did not declare " + handle);
        }
        return handle.type.cast(graph.value(handle.index));
    }
    /** @return current manager */
    public RenderManager getRenderManager() {
        check();
        return manager;
    }
    /** @return current viewport */
    public ViewPort getViewPort() {
        check();
        return viewport;
    }
    /** @return supplied queue, or null when execute was called without one */
    public RenderQueue getRenderQueue() {
        check();
        return queue;
    }
    /**
     * Stops execution after this callback and invalidates exports. Use only before
     * consuming geometry if the caller intends to fall back to Forward rendering.
     * Resources are returned to the pool; already submitted draws are not undone.
     */
    public void abort() {
        check();
        aborted = true;
    }
}

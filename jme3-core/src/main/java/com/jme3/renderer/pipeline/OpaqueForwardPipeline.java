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
 * Leaves all geometry for the engine's existing Forward queue renderer.
 * <pre>{@code
 * // Force this viewport to use Forward even when the manager requests deferred.
 * renderManager.setPipeline(viewPort, new OpaqueForwardPipeline());
 * // Later, restore manager/viewport RenderPath selection:
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 * Install on the render thread and use a separate instance per viewport.
 * RenderManager owns initialization and cleanup. Removing this override does
 * not change the underlying RenderPath settings.
 */
public final class OpaqueForwardPipeline implements OpaquePipeline {
    private boolean initialized;
    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        if (initialized) {
            throw new IllegalStateException("Pipeline already initialized");
        }
        initialized = true;
    }
    @Override
    public boolean isInitialized() {
        return initialized;
    }
    @Override
    public void reshape(int width, int height) {
    }
    @Override
    public void render(RenderQueue queue) {
        if (!initialized) {
            throw new IllegalStateException("Pipeline not initialized");
        }
    }
    @Override
    public void cleanup() {
        initialized = false;
    }
}

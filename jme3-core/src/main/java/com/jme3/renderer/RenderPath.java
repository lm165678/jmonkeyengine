/*
 * Copyright (c) 2009-2019 jMonkeyEngine
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
package com.jme3.renderer;

/**
 * Requested viewport rendering strategy, not a report of the active backend.
 * Use {@code renderManager.setRenderPath(RenderPath.TiledDeferredGPU)} for the
 * default or {@code viewPort.setRenderPath(RenderPath.Forward)} for one viewport.
 * Explicitly installed pipelines take precedence over these settings.
 * Inherit is only valid as a viewport override.
 */
public enum RenderPath {
    /** Follow the manager default; only valid on ViewPort. */
    Inherit("Inherit"),
    /** Original renderer; the RenderManager default. */
    Forward("Forward"),
    /** Deferred opaque lighting without tiled light lists. */
    Deferred("Deferred"),
    /** Build tiled light lists on the CPU; deferred rendering requires OpenGL 3.3. */
    TiledDeferredCPU("Tiled deferred (CPU)"),
    /** Request compute/SSBO culling (OpenGL 4.3), with CPU fallback. */
    TiledDeferredGPU("Tiled deferred (GPU)"),
    /** Prefer GPU tiled culling, otherwise CPU; unsupported viewports use Forward. */
    Auto("Tiled deferred (auto)");

    private final String description;
    RenderPath(String description) { this.description=description; }
    public String getInfo() { return description; }
}

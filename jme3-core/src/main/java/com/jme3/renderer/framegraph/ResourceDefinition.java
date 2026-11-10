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

import com.jme3.renderer.Renderer;

/**
 * Allocation contract for graph-owned resources. Immutable definitions whose
 * equals/hashCode match may reuse the SAME physical object when lifetimes do not
 * overlap. Include every allocation/property requirement in equality; default
 * identity equality safely disables sharing between different definitions.
 * A pass must initialize/clear a written resource: pooled contents are undefined.
 * @param <T> resource type
 */
public interface ResourceDefinition<T> {
    /** @return physical resource class */
    Class<T> getType();
    /**
     * Allocates lazily at first use on the render thread. If allocation fails,
     * the implementation must release any partially constructed objects itself.
     * @param renderer active renderer
     * @param width current viewport width
     * @param height current viewport height
     * @return non-null physical resource
     */
    T create(Renderer renderer, int width, int height);
    /**
     * Releases an owned object, normally with NativeObject.dispose(). Called once
     * per allocation when the pool is invalidated, resized, or closed.
     * @param resource object previously created by this definition
     */
    void destroy(T resource);
}

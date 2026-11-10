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

import com.jme3.light.Light;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import java.util.ArrayList;
import java.util.List;

/** Reused frame data passed between independent passes; owns no native objects. */
final class DeferredRenderContext {
    final RenderManager manager;
    final ViewPort viewport;
    final Renderer renderer;
    final DeferredResources resources;
    final ShadingModelRegistry models;
    final List<Light> lights = new ArrayList<>();
    final Vector3f ambient = new Vector3f();
    DeferredBatch batch;
    int tileSize, columns, rows, tileCapacity;
    boolean gpu, untiled;
    String fallbackReason = "";
    LightCullingMode cullingMode;

    DeferredRenderContext(RenderManager manager, ViewPort viewport, DeferredResources resources,
        ShadingModelRegistry models) {
        this.manager = manager;
        this.viewport = viewport;
        this.renderer = manager.getRenderer();
        this.resources = resources;
        this.models = models;
    }
    Camera camera() {
        return viewport.getCamera();
    }
    static void uniform(Shader shader, String name, VarType type, Object value) {
        shader.getUniform(name).setValue(type, value);
    }
}

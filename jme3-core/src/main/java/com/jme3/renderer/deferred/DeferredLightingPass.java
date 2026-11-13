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
import com.jme3.material.RenderState;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import java.util.List;
import static com.jme3.renderer.deferred.DeferredRenderContext.uniform;

/** Composes the lit surfaces into the viewport while preserving scene depth. */
final class DeferredLightingPass implements DeferredPass {
    private final RenderState lightingState = new RenderState();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector2f resolution = new Vector2f();
    DeferredLightingPass() {
        lightingState.setDepthTest(true);
        lightingState.setDepthWrite(true);
        lightingState.setDepthFunc(RenderState.TestFunction.LessOrEqual);
        lightingState.setFaceCullMode(RenderState.FaceCullMode.Off);
    }
    @Override
    public boolean execute(DeferredRenderContext frame) {
        RenderManager rm = frame.manager;
        Renderer renderer = frame.renderer;
        ViewPort vp = frame.viewport;
        Camera camera = frame.camera();
        boolean gpu = frame.gpu;
        int columns = frame.columns, tileSize = frame.tileSize, tileCapacity = frame.tileCapacity;
        List<Light> lights = frame.lights;
        Vector3f ambient = frame.ambient;
        Shader shader = frame.resources.lightingShader(gpu, frame.models);
        renderer.setFrameBuffer(vp.getOutputFrameBuffer());
        rm.setCamera(camera, false);
        for (int i = 0; i < 6; i++) {
            bindTexture(renderer, i, frame.resources.targets[i]);
        }
        bindTexture(renderer, 6, frame.resources.lightTexture);
        if (!gpu) {
            bindTexture(renderer, 7, frame.resources.tileTexture);
        } else {
            shader.getBufferBlock("TileIndices").setBufferObject(com.jme3.shader.ShaderBufferBlock.BufferType.ShaderStorageBufferObject, frame.resources.gpuIndices);
        }
        String[] names = {
            "gbuffer0", "gbuffer1", "gbuffer2", "gbuffer3", "gbuffer4", "gbufferDepth"};
        for (int i = 0; i < names.length; i++) {
            uniform(shader, names[i], VarType.Int, i);
        }
        uniform(shader, "Lights", VarType.Int, 6);
        uniform(shader, "Tiles", VarType.Int, 7);
        bindTexture(renderer,
            8, frame.batch.colorRamp == null ? frame.resources.targets[0] : frame.batch.colorRamp);
        uniform(shader, "ColorRamp", VarType.Int, 8);
        uniform(shader, "Untiled", VarType.Boolean, frame.untiled);
        uniform(shader, "Columns", VarType.Int, columns);
        uniform(shader, "TileSize", VarType.Int, tileSize);
        uniform(shader, "LightCount", VarType.Int, lights.size());
        uniform(shader, "TileCapacity", VarType.Int, tileCapacity);
        uniform(shader, "TileTextureWidth", VarType.Int,
            gpu ? 1 : frame.resources.tileTexture.getImage().getWidth());
        inverse.set(camera.getViewProjectionMatrix()).invertLocal();
        uniform(shader, "InverseViewProjection", VarType.Matrix4, inverse);
        uniform(shader, "CameraPosition", VarType.Vector3, camera.getLocation());
        uniform(shader, "AmbientColor", VarType.Vector3, ambient);
        resolution.set(camera.getWidth(), camera.getHeight());
        uniform(shader, "Resolution", VarType.Vector2, resolution);
        renderer.applyRenderState(lightingState);
        renderer.setShader(shader);
        renderer.renderMesh(frame.resources.quad, 0, 1, null);
        return true;
    }
    private static void bindTexture(Renderer renderer, int unit, com.jme3.texture.Texture texture) {
        try {
            renderer.setTexture(unit, texture);
        } catch (com.jme3.renderer.TextureUnitException exception) {
            throw new com.jme3.renderer.RendererException(exception);
        }
    }
}

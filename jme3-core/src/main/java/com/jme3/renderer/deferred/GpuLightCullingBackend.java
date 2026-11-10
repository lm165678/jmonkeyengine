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
import com.jme3.math.Vector2f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Limits;
import com.jme3.renderer.Renderer;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import java.util.EnumSet;
import java.util.List;
import static com.jme3.renderer.deferred.DeferredRenderContext.uniform;

final class GpuLightCullingBackend implements LightCullingBackend {
    private final Vector2f resolution = new Vector2f();
    static long records(int columns, int rows, int capacity) {
        return (long) columns * rows * (capacity + 1);
    }
    static boolean supported(Renderer renderer, long records) {
        return renderer.getCaps().containsAll(EnumSet.of(Caps.OpenGL43, Caps.ShaderStorageBufferObject))
                && ((records + 3) / 4) * 16 <= Math.min(64L * 1024 * 1024,
                        renderer.getLimits().get(Limits.ShaderStorageBufferObjectMaxBlockSize));
    }
    @Override public boolean prepare(DeferredRenderContext frame) {
        Camera camera = frame.camera();
        Renderer renderer = frame.renderer;
        int columns = frame.columns, rows = frame.rows, tileSize = frame.tileSize;
        int tileCapacity = frame.tileCapacity;
        List<Light> lights = frame.lights;
        frame.resources.ensureGpuIndices((int) records(columns, rows, tileCapacity));
        Shader computeShader = frame.resources.computeShader();
        computeShader.getBufferBlock("TileIndices").setBufferObject(frame.resources.gpuIndices);
        renderer.setTexture(6,frame.resources.lightTexture);
        uniform(computeShader,"Lights",VarType.Int,6);
        uniform(computeShader,"View",VarType.Matrix4,camera.getViewMatrix());
        uniform(computeShader,"Projection",VarType.Matrix4,camera.getProjectionMatrix());
        uniform(computeShader,"Resolution",VarType.Vector2,resolution.set(camera.getWidth(),camera.getHeight()));
        uniform(computeShader,"Near",VarType.Float,camera.getFrustumNear());
        uniform(computeShader,"Far",VarType.Float,camera.getFrustumFar());
        uniform(computeShader,"Columns",VarType.Int,columns);uniform(computeShader,"TileSize",VarType.Int,tileSize);
        uniform(computeShader,"LightCount",VarType.Int,lights.size());
        uniform(computeShader,"TileCapacity",VarType.Int,tileCapacity);
        // Legacy ramps/reflections can contribute outside the physical light sphere.
        // Keep their existing conservative screen bounds and accumulation semantics.
        boolean conservative = frame.batch.colorRamp != null;
        for (com.jme3.scene.Geometry geometry : frame.batch.geometries) {
            conservative |= GBufferPass.parameter(geometry, "EnvMap") != null;
        }
        uniform(computeShader,"ConservativeBounds",VarType.Boolean,conservative);
        renderer.setShader(computeShader);renderer.dispatchCompute(columns,rows,1);
        return true;
    }
}

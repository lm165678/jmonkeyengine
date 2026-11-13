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

import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.material.TechniqueDef;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.shader.VarType;

/** Runs native surface techniques without changing material instances. */
final class GBufferPass implements DeferredPass {
    private final MatParamOverride lightDataOverride =
        new MatParamOverride(VarType.Texture2D, "DeferredLightData", null);
    private final MatParamOverride lightCountOverride =
        new MatParamOverride(VarType.Int, "DeferredLightCount", 0);
    private final MatParamOverride shadingIdOverride =
        new MatParamOverride(VarType.Int, "DeferredShadingId", 0);

    @Override
    public void reset() {
        lightDataOverride.setValue(null);
        lightCountOverride.setValue(0);
        shadingIdOverride.setValue(0);
    }

    @Override
    public boolean execute(DeferredRenderContext frame) {
        RenderManager rm = frame.manager;
        Renderer renderer = frame.renderer;
        Camera camera = frame.camera();
        lightDataOverride.setValue(frame.resources.lightTexture);
        lightCountOverride.setValue(frame.lights.size());
        String oldTechnique = rm.getForcedTechnique();
        try {
            renderer.setFrameBuffer(frame.resources.gbuffer);
            renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
            renderer.clearBuffers(true, true, true);
            rm.setCamera(camera, false);
            rm.setForcedTechnique("GBuffer");
            for (Geometry geometry : frame.batch.geometries) {
                Material material = geometry.getMaterial();
                String previous = material.getActiveTechnique() == null
                    ? TechniqueDef.DEFAULT_TECHNIQUE_NAME
                    : material.getActiveTechnique().getDef().getName();
                shadingIdOverride.setValue(frame.models.resolve(
                    material.getMaterialDef(), frame.resources.getAssetManager()));
                rm.addForcedMatParam(shadingIdOverride);
                boolean needsLightData = Boolean.TRUE.equals(parameter(geometry, "VertexLighting"))
                    || Boolean.TRUE.equals(parameter(geometry, "UseVertexColorsAsSunIntensity"))
                    || parameter(geometry, "StaticSunIntensity") != null
                    || Boolean.TRUE.equals(parameter(geometry, "UseVertexColorsAsSunExposure"))
                    || parameter(geometry, "StaticSunExposure") != null
                    || parameter(geometry, "SunLightExposureMap") != null;
                if (needsLightData) {
                    rm.addForcedMatParam(lightDataOverride);
                    rm.addForcedMatParam(lightCountOverride);
                }
                try {
                    rm.renderGeometry(geometry);
                } finally {
                    rm.removeForcedMatParam(shadingIdOverride);
                    if (needsLightData) {
                        rm.removeForcedMatParam(lightDataOverride);
                        rm.removeForcedMatParam(lightCountOverride);
                    }
                    material.selectTechnique(previous, rm);
                }
            }
            return true;
        } finally {
            rm.setForcedTechnique(oldTechnique);
        }
    }
    static Object parameter(Geometry geometry, String name) {
        Object value = geometry.getMaterial().getParamValue(name);
        for (MatParamOverride override : geometry.getWorldMatParamOverrides()) {
            if (override.isEnabled() && name.equals(override.getName())) {
                value = override.getValue();
            }
        }
        return value;
    }
}

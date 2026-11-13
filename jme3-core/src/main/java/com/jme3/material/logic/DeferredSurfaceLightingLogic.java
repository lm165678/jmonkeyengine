/*
 * Copyright (c) 2009-2015 jMonkeyEngine
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
package com.jme3.material.logic;

import com.jme3.asset.AssetManager;
import com.jme3.light.*;
import com.jme3.material.TechniqueDef;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.shader.*;
import java.util.EnumSet;

/** Evaluates ambient/probe illumination once while writing material surfaces. */
public final class DeferredSurfaceLightingLogic implements TechniqueDefLogic {
    private final SinglePassAndImageBasedLightingLogic indirect;
    private final LightList indirectLights = new LightList(null);

    public DeferredSurfaceLightingLogic(TechniqueDef definition) {
        indirect = new SinglePassAndImageBasedLightingLogic(definition);
    }

    private LightList collect(LightList lights) {
        indirectLights.clear();
        if (lights != null) for (int i = 0; i < lights.size(); i++) {
            Light light = lights.get(i);
            if (light.isEnabled() && (light instanceof AmbientLight || light instanceof LightProbe)) {
                indirectLights.add(light);
            }
        }
        return indirectLights;
    }

    @Override
    public Shader makeCurrent(AssetManager assets, RenderManager manager, EnumSet<Caps> caps,
                              LightList lights, DefineList defines) {
        return indirect.makeCurrent(assets, manager, caps, collect(lights), defines);
    }

    @Override
    public void render(RenderManager manager, Shader shader, Geometry geometry, LightList lights, com.jme3.material.Material.BindUnits unit) {
        // The delegate consumes the temporary list, never the scene's light list.
        indirect.render(manager, shader, geometry, collect(lights), unit);
    }
}

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

import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.LightProbe;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Limits;
import com.jme3.texture.Image;
import java.nio.ByteBuffer;
import java.util.List;

/** Uploads enabled lights and selects a CPU/GPU implementation by capability. */
final class LightCullingPass implements DeferredPass {
    private final LightCullingBackend cpu = new CpuLightCullingBackend();
    private final LightCullingBackend gpu = new GpuLightCullingBackend();

    @Override public void reset() { cpu.reset(); gpu.reset(); }

    @Override public boolean execute(DeferredRenderContext frame) {
        frame.lights.clear();
        frame.ambient.set(0, 0, 0);
        frame.gpu = false;
        frame.untiled = frame.cullingMode == LightCullingMode.None;
        frame.fallbackReason = "";
        for (Light light : frame.batch.lights) {
            if (!light.isEnabled()) continue;
            if (light instanceof AmbientLight) {
                ColorRGBA color = light.getColor();
                frame.ambient.addLocal(color.r, color.g, color.b);
            } else if (!(light instanceof LightProbe)) frame.lights.add(light);
        }
        if (frame.lights.size() > frame.renderer.getLimits().get(Limits.TextureSize)) {
            frame.fallbackReason = "Light texture capacity exceeded";
            return false;
        }
        uploadLights(frame);
        frame.columns = (frame.camera().getWidth() + frame.tileSize - 1) / frame.tileSize;
        frame.rows = (frame.camera().getHeight() + frame.tileSize - 1) / frame.tileSize;
        frame.tileCapacity = Math.max(1,
                Integer.highestOneBit(Math.max(1, frame.lights.size() - 1)) * 2);
        if (frame.untiled) {
            DeferredResources r = frame.resources;
            r.tileTexture = r.storage(r.tileTexture, 1, 1, Image.Format.R32F, 4);
            r.tileTexture.getImage().getData(0).putFloat(0, 0);
            r.tileTexture.getImage().setUpdateNeeded();
            return true;
        }
        // Dense tiles must not routinely fall back to evaluating every scene light
        // per pixel. Keep the complete list when it fits the bounded SSBO budget.
        // On larger viewports retain smaller lists and the correctness fallback.
        while (frame.tileCapacity > 128 && !GpuLightCullingBackend.supported(frame.renderer,
                GpuLightCullingBackend.records(frame.columns, frame.rows, frame.tileCapacity))) {
            frame.tileCapacity /= 2;
        }
        long records = GpuLightCullingBackend.records(frame.columns, frame.rows, frame.tileCapacity);
        frame.gpu = frame.cullingMode != LightCullingMode.CPU
                && GpuLightCullingBackend.supported(frame.renderer, records);
        if (frame.cullingMode == LightCullingMode.GPU && !frame.gpu) {
            frame.fallbackReason = "GPU unavailable or SSBO capacity exceeded; using CPU";
        }
        return (frame.gpu ? gpu : cpu).prepare(frame);
    }
    private void uploadLights(DeferredRenderContext frame) {
        DeferredResources r = frame.resources;
        List<Light> lights = frame.lights;
        r.lightTexture=r.storage(r.lightTexture,3,Math.max(1,lights.size()),Image.Format.RGBA32F,48*Math.max(1,lights.size()));
        ByteBuffer data=r.lightTexture.getImage().getData(0);
        for(Light light:lights) {
            ColorRGBA c=light.getColor();
            int type=light instanceof DirectionalLight?0:light instanceof PointLight?1:2;
            data.putFloat(c.r).putFloat(c.g).putFloat(c.b).putFloat(type);
            Vector3f p; float inverseRadius=0;
            if(type==0) p=((DirectionalLight)light).getDirection();
            else if(type==1) { PointLight l=(PointLight)light;p=l.getPosition();if(l.getRadius()>0)inverseRadius=1/l.getRadius(); }
            else { SpotLight l=(SpotLight)light;p=l.getPosition();if(l.getSpotRange()>0)inverseRadius=1/l.getSpotRange(); }
            data.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(inverseRadius);
            if(type==2) {SpotLight l=(SpotLight)light;p=l.getDirection();data.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(l.getPackedAngleCos());}
            else data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        }
        data.clear();r.lightTexture.getImage().setUpdateNeeded();
    }
}

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
package com.jme3.post.filters;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.post.FilterPostProcessor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UpscalingSettingsTest {
    @Test
    public void captureScopesNestAndSerializeUserChoices() throws Exception {
        DesktopAssetManager assets = new DesktopAssetManager();
        UpscalingPass pass = new UpscalingPass();
        pass.setAlgorithm(UpscalingPass.Algorithm.FSR2);
        var first = pass.suspendForCapture();
        var second = pass.suspendForCapture();
        assertFalse(pass.isEnabled());
        assertTrue(BinaryExporter.saveAndLoad(assets, pass).isEnabled());
        first.close();
        first.close();
        assertFalse(pass.isEnabled());
        second.close();
        assertTrue(pass.isEnabled());
        try (var scope = pass.suspendForCapture()) {
            pass.setEnabled(false);
        }
        assertFalse(pass.isEnabled());
        TemporalAAFilter taa = new TemporalAAFilter();
        try (var scope = taa.suspendForCapture()) {
            assertFalse(taa.isEnabled());
            assertTrue(BinaryExporter.saveAndLoad(assets, taa).isEnabled());
        }
        assertTrue(taa.isEnabled());
    }

    @Test
    public void serializedPipelinePreservesAlgorithmScaleAndAaPreferences() throws Exception {
        DesktopAssetManager assets = new DesktopAssetManager();
        FilterPostProcessor chain = new FilterPostProcessor(assets);
        chain.setFrameBufferFormat(com.jme3.texture.Image.Format.RGBA16F);
        chain.setNumSamples(1);
        TemporalAAFilter taa = new TemporalAAFilter();
        FXAAFilter fxaa = new FXAAFilter();
        fxaa.setEnabled(false);
        UpscalingPass upscale = new UpscalingPass();
        upscale.setAlgorithm(UpscalingPass.Algorithm.FSR2);
        upscale.setRenderScale(.5f);
        upscale.setSharpness(.4f);
        chain.addFilter(taa);
        chain.addFilter(upscale);
        chain.addFilter(fxaa);
        assertTrue(chain.isFilterBypassed(taa));
        chain.removeFilter(taa);
        assertFalse(chain.isFilterBypassed(taa));
        chain.addFilter(taa);
        assertTrue(chain.isFilterBypassed(taa));
        FilterPostProcessor copy = BinaryExporter.saveAndLoad(assets, chain);
        assertEquals(com.jme3.texture.Image.Format.RGBA16F, copy.getFrameBufferFormat());
        UpscalingPass saved = copy.getFilter(UpscalingPass.class);
        assertEquals(UpscalingPass.Algorithm.FSR2, saved.getAlgorithm());
        assertEquals(.5f, saved.getRenderScale(), 0);
        assertEquals(.4f, saved.getSharpness(), 0);
        assertTrue(copy.getFilter(TemporalAAFilter.class).isEnabled());
        assertFalse(copy.getFilter(FXAAFilter.class).isEnabled());
        saved.setEnabled(false);
        assertTrue(copy.getFilter(TemporalAAFilter.class).isEnabled());
    }
}

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
import com.jme3.renderer.Camera;
import com.jme3.light.*;
import com.jme3.math.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TileLightGridTest {
    private Camera camera() {
        Camera c = new Camera(65, 49);
        c.setFrustumPerspective(60, 65f / 49, .1f, 100);
        c.lookAtDirection(new Vector3f(0, 0, -1), Vector3f.UNIT_Y);
        c.update();
        return c;
    }
    @Test
    public void directionalCoversPartialEdgeTiles() {
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), Arrays.asList(new DirectionalLight()));
        assertEquals(5, g.getColumns());
        assertEquals(4, g.getRows());
        assertEquals(1, g.getLightCount(4, 3));
    }
    @Test
    public void behindCameraIsRejected() {
        PointLight p = new PointLight();
        p.setPosition(new Vector3f(0, 0, 10));
        p.setRadius(1);
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), Arrays.asList(p));
        assertEquals(0, g.getLightCount(2, 1));
    }
    @Test
    public void nearPlaneIntersectionIsConservative() {
        PointLight p = new PointLight();
        p.setPosition(new Vector3f(0, 0, -.1f));
        p.setRadius(1);
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), Arrays.asList(p));
        assertEquals(1, g.getLightCount(4, 3));
    }
    @Test
    public void overflowNeverDropsLightsAndRebuildClears() {
        List<Light> lights = new ArrayList<>();
        for (int i = 0; i < 513; i++) {
            lights.add(new DirectionalLight());
        }
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), lights);
        assertEquals(513, g.getLightCount(0, 0));
        assertEquals(512, g.getLightIndex(0, 0, 512));
        g.build(camera(), Collections.<Light>emptyList());
        assertEquals(0, g.getLightCount(0, 0));
    }
    @Test
    public void smallPointDoesNotCoverEntireScreen() {
        PointLight p = new PointLight();
        p.setPosition(new Vector3f(0, 0, -10));
        p.setRadius(.1f);
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), Arrays.asList(p));
        assertEquals(0, g.getLightCount(0, 0));
        assertEquals(1, g.getLightCount(2, 1));
    }
    @Test
    public void unlimitedAndDisabledLights() {
        PointLight p = new PointLight();
        p.setRadius(0);
        DirectionalLight d = new DirectionalLight();
        d.setEnabled(false);
        TileLightGrid g = new TileLightGrid(16);
        g.build(camera(), Arrays.asList(p, d));
        assertEquals(1, g.getLightCount(4, 3));
    }
}

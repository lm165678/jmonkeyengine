package com.jme3.environment.gi;

import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import org.junit.Test;
import static org.junit.Assert.*;

/** Total captures scale sky pixels once; surface pixels already contain lighting. */
public class GiCaptureSkyIntensityTest {
    @Test
    public void totalSkyHonorsIntensityAndDiffuseToggle() {
        assertEquals(0.5f, sample(false, true, true, 0.25f, true), 0.00001f);
        assertEquals(0f, sample(false, false, true, 1f, true), 0f);
        assertEquals(0f, sample(false, true, false, 1f, true), 0f);
        assertEquals(2f, sample(false, true, true, 0.25f, false), 0f);
        assertEquals(0f, sample(true, true, true, 1f, true), 0f);
        assertFalse(new GiSceneBaker.Settings().separateSky);
    }

    private float sample(boolean separate, boolean diffuse, boolean enabled,
            float intensity, boolean background) {
        Node scene = new Node();
        SkyLight sky = new SkyLight();
        sky.setReady(true);
        sky.setEnabled(enabled);
        sky.setDiffuseEnabled(diffuse);
        sky.setIntensity(intensity);
        scene.addLight(sky);
        GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
        settings.captureSize = 4;
        settings.separateSky = separate;
        GiSceneBaker.Capture capture = new GiSceneBaker.Capture(settings, scene);
        try {
            for (int face = 0; face < 6; face++) {
                for (int pixel = 0; pixel < 16; pixel++) {
                    for (int channel = 0; channel < 4; channel++) {
                        capture.colors[face].putFloat(pixel * 16 + channel * 4, 2f);
                    }
                    capture.depths[face].putFloat(pixel * 4, background ? 1f : .5f);
                }
            }
            Vector3f result = new Vector3f();
            capture.color(Vector3f.UNIT_X, result);
            return result.x;
        } finally {
            for (int face = 0; face < 6; face++) {
                com.jme3.util.BufferUtils.destroyDirectBuffer(capture.colors[face]);
                com.jme3.util.BufferUtils.destroyDirectBuffer(capture.depths[face]);
            }
        }
    }
}

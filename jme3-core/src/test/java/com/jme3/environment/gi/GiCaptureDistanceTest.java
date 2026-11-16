package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiCaptureDistanceTest {
    @Test
    public void reconstructsSlantedPlaneBetweenCapturePixels() {
        GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
        settings.captureSize = 16;
        GiSceneBaker.Capture capture = new GiSceneBaker.Capture(settings, new Node());
        try {
            // +Z face has right=-X and up=+Y. Plane z=4+0.4*x+0.2*y.
            for (int y = 0; y < settings.captureSize; y++) {
                for (int x = 0; x < settings.captureSize; x++) {
                    float u = 2f * (x + 0.5f) / settings.captureSize - 1;
                    float v = 2f * (y + 0.5f) / settings.captureSize - 1;
                    float axial = 4 / (1 + 0.4f * u - 0.2f * v);
                    capture.depths[4].putFloat((y * settings.captureSize + x) * 4, depth(settings, axial));
                }
            }
            for (int i = 0; i < 80; i++) {
                Vector3f ray = new Vector3f(-0.75f + i * 0.018f, 0.173f, 1).normalizeLocal();
                float expected = 4 / (ray.z - 0.4f * ray.x - 0.2f * ray.y);
                assertEquals("Capture texels must not stamp angular steps into a plane", expected,
                        capture.distance(ray), 0.00015f);
            }
        } finally {
            release(capture);
        }
    }

    @Test
    public void doesNotBlendSkyIntoAnOccluder() {
        GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
        settings.captureSize = 8;
        GiSceneBaker.Capture capture = new GiSceneBaker.Capture(settings, new Node());
        try {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    capture.depths[4].putFloat((y * 8 + x) * 4, x < 4 ? depth(settings, 2) : 1);
                }
            }
            Vector3f blocked = new Vector3f(0.01f, 0, 1).normalizeLocal();
            assertEquals(2 / blocked.z, capture.distance(blocked), 0.0001f);
            assertEquals(settings.far, capture.distance(new Vector3f(-0.01f, 0, 1).normalizeLocal()), 0);
        } finally {
            release(capture);
        }
    }

    private static float depth(GiSceneBaker.Settings settings, float axial) {
        return (settings.far - settings.near * settings.far / axial) / (settings.far - settings.near);
    }

    private static void release(GiSceneBaker.Capture capture) {
        for (int face = 0; face < 6; face++) {
            BufferUtils.destroyDirectBuffer(capture.colors[face]);
            BufferUtils.destroyDirectBuffer(capture.depths[face]);
        }
    }
}

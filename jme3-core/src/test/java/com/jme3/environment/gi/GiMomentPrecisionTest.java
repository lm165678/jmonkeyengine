package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiMomentPrecisionTest {
    private GiProbeData bake(float mean, float variance, float scale) {
        float[][] sh = new float[8][27];
        float[][] distances = new float[8][8];
        for (float[] values : distances) {
            for (int i = 0; i < values.length; i += 2) {
                values[i] = mean;
                values[i + 1] = mean * mean + variance;
            }
        }
        return new GiProbeData(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2),
                2, sh, distances, null, null, true, scale);
    }

    @Test
    public void retainsVarianceThatLegacyHalfMomentsLose() {
        GiProbeData data = bake(0.045f, 0.000001f, 1);
        try {
            Image image = data.moments().getImage();
            float mean = GiProbeData.component(image, 0);
            float second = GiProbeData.component(image, 1);
            assertTrue("Fixture must reproduce negative legacy variance", second - mean * mean < 0);
            float deviation = GiProbeData.component(image, 2);
            assertEquals(0.000001f, deviation * deviation, 0.000000005f);
            assertEquals(1, GiProbeData.component(image, 3), 0);
        } finally {
            data.dispose();
        }
    }

    @Test
    public void standardDeviationSurvivesLargeCaptureRangeAndSerialization() throws Exception {
        GiProbeData data = bake(1, 0.0001f, 1000);
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            data.saveCompressed(output);
            GiProbeData restored = GiProbeData.loadCompressed(new java.io.ByteArrayInputStream(output.toByteArray()));
            try {
                float deviation = GiProbeData.component(restored.moments().getImage(), 2);
                assertEquals(0.0001, deviation * deviation * 1000000.0, 0.000002);
                assertEquals(1, GiProbeData.component(restored.moments().getImage(), 3), 0);
            } finally {
                restored.dispose();
            }
        } finally {
            data.dispose();
        }
    }

    @Test
    public void doesNotManufactureVarianceForExactOcclusion() {
        GiProbeData data = bake(0.5f, 0, 1);
        try {
            assertEquals(0, GiProbeData.component(data.moments().getImage(), 2), 0);
        } finally {
            data.dispose();
        }
    }
}

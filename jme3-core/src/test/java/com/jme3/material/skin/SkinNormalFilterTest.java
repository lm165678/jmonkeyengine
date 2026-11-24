package com.jme3.material.skin;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkinNormalFilterTest {
    @Test
    void constantNormalsStayUnitLengthWithoutGammaConversion() {
        float[] data = new float[17 * 9 * 3];
        for (int i = 0; i < data.length; i += 3) {
            data[i] = .8f;
            data[i + 1] = .5f;
            data[i + 2] = .9f;
        }
        for (SkinNormalFilter.Quality quality : SkinNormalFilter.Quality.values()) {
            var result = SkinNormalFilter.generate(data, 17, 9, SkinLutGenerator.referenceProfile(),
                    4f, SkinNormalFilter.Boundary.CLAMP, quality);
            for (int c = -1; c < 3; c++) {
                assertEquals(.6f, result.get(c, 8, 4, 0), 1e-5f);
                assertEquals(0f, result.get(c, 8, 4, 1), 1e-5f);
                assertEquals(.8f, result.get(c, 8, 4, 2), 1e-5f);
            }
        }
    }

    @Test
    void redDiffusesDetailsMoreThanGreenAndBlue() {
        float[] data = waves(65, 17, .8);
        var result = SkinNormalFilter.generate(data, 65, 17, SkinLutGenerator.referenceProfile(),
                4f, SkinNormalFilter.Boundary.WRAP, SkinNormalFilter.Quality.EXACT);
        double[] variation = new double[3];
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 65; x++) {
                for (int c = 0; c < 3; c++) {
                    variation[c] += Math.abs(result.get(c, x, y, 0));
                    double length = 0.0;
                    for (int component = 0; component < 3; component++) {
                        double value = result.get(c, x, y, component);
                        length += value * value;
                    }
                    assertEquals(1.0, length, 1e-5);
                }
            }
        }
        assertTrue(variation[0] < variation[1] && variation[1] < variation[2]);
    }

    @Test
    void wrapIsTranslationInvariantAndFastFilterApproximatesExact() {
        float[] data = waves(64, 8, Math.PI / 16.0);
        float[] shifted = new float[data.length];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 64; x++) {
                System.arraycopy(data, (y * 64 + x) * 3, shifted, (y * 64 + (x + 7) % 64) * 3, 3);
            }
        }
        var profile = new SkinLutGenerator.Profile(new float[]{16f}, new float[][]{{1f, 1f, 1f}}, new float[]{1f, 1f, 1f});
        var exact = SkinNormalFilter.generate(data, 64, 8, profile, 2f, SkinNormalFilter.Boundary.WRAP, SkinNormalFilter.Quality.EXACT);
        var moved = SkinNormalFilter.generate(shifted, 64, 8, profile, 2f, SkinNormalFilter.Boundary.WRAP, SkinNormalFilter.Quality.EXACT);
        var fast = SkinNormalFilter.generate(data, 64, 8, profile, 2f, SkinNormalFilter.Boundary.WRAP, SkinNormalFilter.Quality.FAST);
        for (int x = 0; x < 64; x++) {
            for (int component = 0; component < 3; component++) {
                assertEquals(exact.get(0, x, 0, component), moved.get(0, (x + 7) % 64, 0, component), 1e-5f);
                assertEquals(exact.get(0, x, 0, component), fast.get(0, x, 0, component), .02f);
            }
        }
    }

    @Test
    void cancellationAndInvalidDensity() {
        float[] data = {.5f, .5f, 1f};
        assertThrows(IllegalArgumentException.class, () -> SkinNormalFilter.generate(data, 1, 1,
                SkinLutGenerator.referenceProfile(), 0f, SkinNormalFilter.Boundary.CLAMP, SkinNormalFilter.Quality.EXACT));
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> SkinNormalFilter.generate(data, 1, 1,
                    SkinLutGenerator.referenceProfile(), 4f, SkinNormalFilter.Boundary.CLAMP, SkinNormalFilter.Quality.EXACT));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void presetsAreDistinctEditableStartingPoints() {
        var reference = SkinLutGenerator.Preset.REFERENCE.createProfile();
        var dark = SkinLutGenerator.Preset.DARK_COMPACT.createProfile();
        assertEquals(1f, reference.distance(0));
        assertTrue(dark.distance(0) < reference.distance(0));
        assertTrue(SkinLutGenerator.Preset.DARK_COMPACT.toString().contains("artist"));
    }

    private static float[] waves(int width, int height, double frequency) {
        float[] data = new float[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float nx = (float) (.6 * Math.sin(x * frequency));
                int offset = (y * width + x) * 3;
                data[offset] = nx * .5f + .5f;
                data[offset + 1] = .5f;
                data[offset + 2] = (float) Math.sqrt(1f - nx * nx) * .5f + .5f;
            }
        }
        return data;
    }
}

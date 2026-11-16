package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiProbeTest {
    @Test
    public void arbitraryCountsRoundTripAndBoundaryWeights() {
        GiProbeGrid grid = new GiProbeGrid(new Vector3f(-4, -6, -8),
                new Vector3f(2, 3, 4), 3, 5, 7);
        int[] indices = new int[8];
        float[] weights = new float[8];
        Vector3f position = new Vector3f();
        for (int i = 0; i < grid.getProbeCount(); i++) {
            grid.position(i, position);
            assertTrue(grid.neighbors(position, indices, weights));
            float sum = 0;
            for (int j = 0; j < 8; j++) {
                sum += weights[j];
                if (weights[j] > 0) {
                    assertEquals(i, indices[j]);
                }
            }
            assertEquals(1f, sum, 0f);
        }
        assertFalse(grid.neighbors(new Vector3f(-5, 0, 0), indices, weights));
        for (float weight : weights) {
            assertEquals(0f, weight, 0f);
        }
    }

    @Test
    public void uniformRadianceIntegratesToPiIrradiance() {
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2);
        int[] completed = {0};
        GiProbeBaker.bake(grid, 4096, (p, d, result) -> result.set(1, 2, 3), (i, sh) -> {
            assertEquals(completed[0]++, i);
            for (int c = 0; c < 3; c++) {
                assertEquals(Math.PI * (c + 1), sh[c] * 0.28209479177387814, 0.0001);
            }
            for (int coefficient = 3; coefficient < 27; coefficient++) {
                assertEquals(0f, sh[coefficient], 0.001f);
            }
        }, () -> false);
        assertEquals(8, completed[0]);
    }

    @Test(expected = java.util.concurrent.CancellationException.class)
    public void cancellationDoesNotPublishProbe() {
        GiProbeBaker.bake(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2),
                32, (p, d, result) -> fail("Cancelled bake sampled source"),
                (i, sh) -> fail("Cancelled bake published probe"), () -> true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedGridRejectedBeforeAllocation() {
        new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, Integer.MAX_VALUE, 3, 3);
    }
}

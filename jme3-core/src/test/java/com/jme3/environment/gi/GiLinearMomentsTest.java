package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiLinearMomentsTest {
    @Test
    public void pageWidthSourceBricksStillFitAfterAddingGutters() {
        float[][] sh = new float[512][27];
        float[][] distances = new float[512][16 * 16 * 2];
        for (int p = 0; p < 512; p++) {
            for (int i = 0; i < 16 * 16; i++) {
                distances[p][i * 2] = p + 1;
                distances[p][i * 2 + 1] = (p + 1f) * (p + 1f);
            }
        }
        GiProbeData source = new GiProbeData(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 8, 8, 8),
                16, sh, distances);
        GiProbeData brick = source.resample(0, 0, 0, 7, 7, 7, 7, 7, 7, 16);
        int width = GiLinearMoments.width(brick);
        int height = GiLinearMoments.height(brick);
        ByteBuffer padded = BufferUtils.createByteBuffer(width * height * 16);
        try {
            assertEquals(1024, brick.moments().getImage().getWidth());
            assertEquals(1008, width);
            assertEquals(126, height);
            for (int y = 0; y < height; y++) {
                GiLinearMoments.copyRow(brick, y, padded, width, y);
            }
            int probe = 342;
            int x = probe % 56 * 18 + 1;
            int y = probe / 56 * 18 + 1;
            assertEquals(439f, padded.getFloat((y * width + x) * 16), 0f);
            assertEquals(0L, padded.getLong((height * width - 1) * 16));
        } finally {
            brick.dispose();
            source.dispose();
            BufferUtils.destroyDirectBuffer(padded);
        }
    }

    @Test
    public void guttersMirrorEdgesWithoutBorrowingAnotherProbe() {
        float[][] sh = new float[8][27];
        float[][] distances = new float[8][8];
        for (int p = 0; p < 8; p++) {
            for (int i = 0; i < 4; i++) {
                float mean = p * 100 + (i / 2) * 10 + i % 2 + 1;
                distances[p][i * 2] = mean;
                distances[p][i * 2 + 1] = mean * mean + 0.25f;
            }
        }
        GiProbeData data = new GiProbeData(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2),
                2, sh, distances);
        int width = GiLinearMoments.width(data);
        int height = GiLinearMoments.height(data);
        ByteBuffer padded = BufferUtils.createByteBuffer(width * height * 16);
        byte[] before = new byte[data.moments().getImage().getData(0).capacity()];
        data.moments().getImage().getData(0).duplicate().get(before);
        try {
            for (int y = 0; y < height; y++) {
                GiLinearMoments.copyRow(data, y, padded, width, y);
            }
            int[] expected = {12, 2, 1, 11, 11, 1, 2, 12, 1, 11, 12, 2, 2, 12, 11, 1};
            for (int p = 0; p < 8; p++) {
                int tx = p % data.columns() * 4;
                int ty = p / data.columns() * 4;
                for (int i = 0; i < 16; i++) {
                    int offset = ((ty + i / 4) * width + tx + i % 4) * 16;
                    assertEquals(p * 100 + expected[i], padded.getFloat(offset), 0);
                    assertEquals(0.25f, padded.getFloat(offset + 8), 0);
                }
            }
            byte[] after = new byte[before.length];
            data.moments().getImage().getData(0).duplicate().get(after);
            assertArrayEquals(before, after);
        } finally {
            data.dispose();
            BufferUtils.destroyDirectBuffer(padded);
        }
    }
}

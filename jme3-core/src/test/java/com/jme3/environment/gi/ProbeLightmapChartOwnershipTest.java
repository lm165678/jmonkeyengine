package com.jme3.environment.gi;

import com.jme3.scene.Mesh;
import com.jme3.math.FastMath;
import com.jme3.scene.VertexBuffer.Type;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProbeLightmapChartOwnershipTest {
    @Test
    public void seamBlendMatchesGeometricEdgesAndPreservesCoverage() {
        int size = 16;
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, new float[]{0, 0, 0, 0, 1, 0, -1, 0, 0,
            0, 1, 0, 0, 0, 0, 1, 0, 0});
        mesh.setBuffer(Type.Normal, 3, new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1,
            0, 0, 1, 0, 0, 1, 0, 0, 1});
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{.25f, .125f, .25f, .875f, .0625f, .125f,
            .75f, .875f, .75f, .125f, .9375f, .125f});
        mesh.updateCounts();
        int[] owners = ProbeLightmapChartOwnership.rasterize(mesh, size, size, 2);
        ByteBuffer pixels = ByteBuffer.allocate(size * size * 8);
        int left = size * 5 + 3;
        int right = size * 5 + 12;
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] == owners[left]) {
                pixels.putShort(i * 8, FastMath.convertFloatToHalf(1f));
                pixels.putShort(i * 8 + 6, FastMath.convertFloatToHalf(.5f));
            } else if (owners[i] == owners[right]) {
                pixels.putShort(i * 8, FastMath.convertFloatToHalf(4f));
                pixels.putShort(i * 8 + 6, FastMath.convertFloatToHalf(1f));
            }
        }
        ProbeLightmapSeamBlend.blend(mesh, pixels, size, size, owners);
        assertTrue(FastMath.convertHalfToFloat(pixels.getShort(left * 8)) > 1f);
        assertTrue(FastMath.convertHalfToFloat(pixels.getShort(right * 8)) < 4f);
        assertEquals(.5f, FastMath.convertHalfToFloat(pixels.getShort(left * 8 + 6)), 0f);
        assertEquals(1f, FastMath.convertHalfToFloat(pixels.getShort(right * 8 + 6)), 0f);
    }

    @Test
    public void fineResolveOnlyFillsMissingSamplesFromTheSameIsland() {
        ByteBuffer resolved = ByteBuffer.allocate(2 * 2 * 16);
        ByteBuffer fine = ByteBuffer.allocate(8 * 8 * 16);
        resolved.putFloat(0, 7f);
        resolved.putFloat(12, 1.5f);
        resolved.putFloat(2 * 16, 3f);
        boolean[] missing = {true, true};
        assertTrue(ProbeLightmapBaker.markMissing(resolved, missing));

        int[] fineOwners = new int[32];
        fineOwners[4] = 1;
        fine.putFloat(4 * 16, 9f);
        fine.putFloat(4 * 16 + 12, 2f);
        fine.putFloat((4 + 32) * 16, 10f);
        fine.putFloat(5 * 16, 100f);
        fine.putFloat(5 * 16 + 12, 2f);

        ProbeLightmapBaker.resolveSubtexels(fine, resolved, 2, 1, true, 4,
                missing, new int[]{0, 1}, fineOwners);
        assertEquals(7f, resolved.getFloat(0), 0f);
        assertEquals(3f, resolved.getFloat(2 * 16), 0f);
        assertEquals(9f, resolved.getFloat(16), 0f);
        assertEquals(10f, resolved.getFloat(3 * 16), 0f);
    }

    @Test
    public void narrowIslandDoesNotBorrowFromCloserTexelOnAnotherIsland() {
        int size = 8;
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, new float[18]);
        float[] uv = {1.2f, 1.2f, 1.6f, 5.3f, 1f, 5.3f,
            2.05f, 1.5f, 3f, 1.5f, 2.05f, 4.5f};
        for (int i = 0; i < uv.length; i++) {
            uv[i] /= size;
        }
        mesh.setBuffer(Type.TexCoord2, 2, uv);
        mesh.updateCounts();
        int[] owners = ProbeLightmapChartOwnership.rasterize(mesh, size, size, 2);
        ByteBuffer raster = ByteBuffer.allocate(size * size * 16);
        int otherIsland = size * 2 + 2;
        int sameIsland = size * 4 + 1;
        raster.putFloat(otherIsland * 16 + 12, 1f);
        assertEquals(otherIsland, ProbeLightmapBaker.nearestRasterized(raster, size, size, 1, 2, null));
        assertEquals(-1, ProbeLightmapBaker.nearestRasterized(raster, size, size, 1, 2, owners));
        raster.putFloat(sameIsland * 16 + 12, 1f);
        assertEquals(sameIsland, ProbeLightmapBaker.nearestRasterized(raster, size, size, 1, 2, owners));
    }
}

package com.jme3.environment.gi;

import com.jme3.math.FastMath;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.BitSet;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class ProbeLightmapMipChainTest {
    private static final int SIZE = 32;
    private static final int SKY_OFFSET = SIZE * SIZE * 8;

    @Test
    public void storesOnlyMipLevelsWithIndependentIslandFootprintsAndSkyBands() {
        Mesh mesh = separatedIslands(.125f);
        var charts = ProbeLightmapChartOwnership.charts(mesh);
        int[] owners = ProbeLightmapChartOwnership.rasterize(charts, SIZE, SIZE, 2, null);
        ByteBuffer base = ByteBuffer.allocate(SKY_OFFSET * 2);
        BitSet filled = colorAtlas(base, owners, charts.owners[0]);
        Image lightmap = ProbeLightmapMipChain.build(mesh, charts, base, owners, filled, SIZE, SIZE, true);
        ByteBuffer mipData = lightmap.getData(0);
        int levelZeroBytes = base.limit();
        // Mip 1 can filter both islands separately. Mip 2 needs the same
        // bilinear tap for both charts, including taps in their gutters.
        assertArrayEquals(new int[]{levelZeroBytes, levelZeroBytes / 4}, lightmap.getMipMapSizes());
        for (int i = 0; i < levelZeroBytes; i++) {
            assertEquals(base.get(i), mipData.get(i));
        }
        int leftMip = (4 * 16 + 3) * 8;
        int rightMip = (4 * 16 + 10) * 8;
        int mipSkyOffset = SKY_OFFSET / 4;
        assertEquals(1f, read(mipData, levelZeroBytes + leftMip), 0f);
        assertEquals(16f, read(mipData, levelZeroBytes + rightMip), 0f);
        assertEquals(0f, read(mipData, levelZeroBytes + leftMip + 6), 0f);
        assertEquals(1f, read(mipData, levelZeroBytes + rightMip + 6), 0f);
        assertEquals(-.5f, read(mipData, levelZeroBytes + mipSkyOffset + leftMip), 0f);
        assertEquals(-5f, read(mipData, levelZeroBytes + mipSkyOffset + rightMip), 0f);
        assertEquals(.25f, read(mipData, levelZeroBytes + mipSkyOffset + leftMip + 6), 0f);
        assertEquals(.875f, read(mipData, levelZeroBytes + mipSkyOffset + rightMip + 6), 0f);
    }

    @Test
    public void avoidsLevelsThatCouldBeFilteredAcrossAStackedBandBoundary() {
        Mesh mesh = separatedIslands(.02f);
        var charts = ProbeLightmapChartOwnership.charts(mesh);
        int[] owners = ProbeLightmapChartOwnership.rasterize(charts, SIZE, SIZE, 2, null);
        ByteBuffer base = ByteBuffer.allocate(SKY_OFFSET * 2);
        BitSet filled = colorAtlas(base, owners, charts.owners[0]);
        Image lightmap = ProbeLightmapMipChain.build(mesh, charts, base, owners, filled, SIZE, SIZE, true);
        assertFalse(lightmap.hasMipmaps());
        assertSame(base, lightmap.getData(0));
    }

    @Test
    public void avoidsMipsForAChartCrossingSimplifiedTriangle() {
        Mesh mesh = separatedIslands(.125f);
        mesh.setBuffer(Type.Index, 3, new short[]{0, 1, 2, 3, 4, 5});
        VertexBuffer lod = new VertexBuffer(Type.Index);
        lod.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.UnsignedShort,
                BufferUtils.createShortBuffer((short) 0, (short) 1, (short) 3));
        mesh.setLodLevels(new VertexBuffer[]{mesh.getBuffer(Type.Index), lod});
        var charts = ProbeLightmapChartOwnership.charts(mesh);
        int[] owners = ProbeLightmapChartOwnership.rasterize(charts, SIZE, SIZE, 2, null);
        ByteBuffer base = ByteBuffer.allocate(SKY_OFFSET * 2);
        BitSet filled = colorAtlas(base, owners, charts.owners[0]);
        Image lightmap = ProbeLightmapMipChain.build(mesh, charts, base, owners, filled, SIZE, SIZE, true);
        assertFalse(lightmap.hasMipmaps());
        assertSame(base, lightmap.getData(0));
    }

    private static Mesh separatedIslands(float bottom) {
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, new float[18]);
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{.125f, bottom, .3125f, bottom, .125f, .625f,
            .5625f, bottom, .75f, bottom, .5625f, .625f});
        mesh.updateCounts();
        return mesh;
    }

    private static BitSet colorAtlas(ByteBuffer base, int[] owners, int left) {
        BitSet filled = new BitSet();
        for (int i = 0; i < owners.length; i++) {
            if (owners[i] < 0) {
                continue;
            }
            filled.set(i);
            boolean leftIsland = owners[i] == left;
            int offset = i * 8;
            base.putShort(offset, FastMath.convertFloatToHalf(leftIsland ? 1f : 16f));
            base.putShort(offset + 6, FastMath.convertFloatToHalf(leftIsland ? 0f : 1f));
            base.putShort(SKY_OFFSET + offset, FastMath.convertFloatToHalf(leftIsland ? -.5f : -5f));
            base.putShort(SKY_OFFSET + offset + 6, FastMath.convertFloatToHalf(leftIsland ? .25f : .875f));
        }
        return filled;
    }

    private static float read(ByteBuffer data, int offset) {
        return FastMath.convertHalfToFloat(data.getShort(offset));
    }
}

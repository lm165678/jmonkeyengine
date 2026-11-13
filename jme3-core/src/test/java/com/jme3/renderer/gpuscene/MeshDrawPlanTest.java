package com.jme3.renderer.gpuscene;

import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.util.BufferUtils;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshDrawPlanTest {
    @Test
    public void packedRangesPreserveSourceBuffersAndPerMeshLods() {
        Mesh first = new Box(1, 1, 1);
        Mesh second = new Box(2, 2, 2);
        VertexBuffer originalFirst = first.getBuffer(VertexBuffer.Type.Index);
        VertexBuffer originalSecond = second.getBuffer(VertexBuffer.Type.Index);
        VertexBuffer coarse = new VertexBuffer(VertexBuffer.Type.Index);
        coarse.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.UnsignedShort,
                BufferUtils.createShortBuffer(new short[]{0, 1, 2, 0, 2, 3}));
        second.setLodLevels(new VertexBuffer[]{originalSecond, coarse});
        try (MeshDrawPlan plan = new MeshDrawPlan(Arrays.asList(new Geometry("a", first),
                new Geometry("b", second), new Geometry("a2", first)), 2, true)) {
            assertEquals(3, plan.sources.size());
            assertEquals(4, plan.visibleCapacity);
            assertEquals(48, plan.packed.getVertexCount());
            assertArrayEquals(new int[]{0, 1, 1, 2, 0, 1}, plan.instanceData);
            assertArrayEquals(new int[]{36, 0, 0, 0, 36, 36, 24, 2, 6, 72, 24, 3}, plan.drawData);
            assertSame(originalFirst, first.getBuffer(VertexBuffer.Type.Index));
            assertSame(originalSecond, second.getBuffer(VertexBuffer.Type.Index));
            assertEquals(36, originalFirst.getData().limit());
            assertNotSame(first.getBuffer(VertexBuffer.Type.Position), plan.packed.getBuffer(VertexBuffer.Type.Position));
        }
        assertEquals(36, originalSecond.getData().limit());
    }

    @Test
    public void mismatchedLayoutsDoNotMerge() {
        Mesh first = new Box(1, 1, 1);
        Mesh second = new Box(2, 2, 2);
        assertTrue(MeshDrawPlan.compatible(first, second));
        second.clearBuffer(VertexBuffer.Type.Normal);
        assertFalse(MeshDrawPlan.compatible(first, second));
    }
}

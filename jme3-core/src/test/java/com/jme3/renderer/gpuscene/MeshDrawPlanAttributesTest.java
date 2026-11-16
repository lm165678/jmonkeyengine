package com.jme3.renderer.gpuscene;

import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.util.TangentBinormalGenerator;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshDrawPlanAttributesTest {
    @Test
    public void shapePackingPreservesAddedTangentBufferWithoutOwningIt() {
        Box box = new Box(1, 1, 1);
        TangentBinormalGenerator.generate(box);
        VertexBuffer tangent = box.getBuffer(VertexBuffer.Type.Tangent);
        VertexBuffer indices = box.getBuffer(VertexBuffer.Type.Index);
        Geometry geometry = new Geometry("normal mapped shape", box);
        try (MeshDrawPlan plan = new MeshDrawPlan(Collections.singletonList(geometry), 1, true)) {
            assertSame(tangent, plan.packed.getBuffer(VertexBuffer.Type.Tangent));
            assertSame(box.getBuffer(VertexBuffer.Type.Position), plan.packed.getBuffer(VertexBuffer.Type.Position));
            assertNotSame(indices, plan.packed.getBuffer(VertexBuffer.Type.Index));
            assertEquals(box.getVertexCount(), plan.packed.getVertexCount());
        }
        assertSame(tangent, box.getBuffer(VertexBuffer.Type.Tangent));
        assertSame(indices, box.getBuffer(VertexBuffer.Type.Index));
    }
}

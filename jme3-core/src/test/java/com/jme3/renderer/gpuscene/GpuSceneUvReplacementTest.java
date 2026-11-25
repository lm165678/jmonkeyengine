package com.jme3.renderer.gpuscene;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.shader.VarType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpuSceneUvReplacementTest {
    @Test
    @SuppressWarnings("unchecked")
    public void replacedOriginalRepresentativeCannotAcceptItsOldBatch() throws Exception {
        Node root = new Node();
        Mesh source = new Box(1, 1, 1);
        MaterialDef definition = new MaterialDef(null, "Batch regression");
        definition.addMaterialParam(VarType.Boolean, "UseInstancing", false);
        Material material = new Material(definition);
        Geometry first = new Geometry("First", source);
        Geometry second = new Geometry("Second", source);
        first.setMaterial(material);
        second.setMaterial(material);
        root.attachChild(first);
        root.attachChild(second);
        root.updateGeometricState();
        GpuScenePipeline pipeline = new GpuScenePipeline(new DesktopAssetManager(true), root);
        var collect = GpuScenePipeline.class.getDeclaredMethod("collect", Spatial.class);
        collect.setAccessible(true);
        collect.invoke(pipeline, root);
        Field batchesField = GpuScenePipeline.class.getDeclaredField("batches");
        batchesField.setAccessible(true);
        List<?> batches = (List<?>) batchesField.get(pipeline);
        assertEquals(1, batches.size());
        Mesh withUv = source.deepClone();
        withUv.setBuffer(VertexBuffer.Type.TexCoord2, 2, new float[source.getVertexCount() * 2]);
        first.setMesh(withUv);
        Field changesField = GpuScenePipeline.class.getDeclaredField("structuralChanges");
        changesField.setAccessible(true);
        ((Set<Spatial>) changesField.get(pipeline)).add(first);
        var apply = GpuScenePipeline.class.getDeclaredMethod("applyStructuralChanges");
        apply.setAccessible(true);
        apply.invoke(pipeline);
        assertEquals(2, batches.size());
        for (Object batch : batches) {
            Field instancesField = batch.getClass().getDeclaredField("instances");
            instancesField.setAccessible(true);
            List<Geometry> instances = (List<Geometry>) instancesField.get(batch);
            assertEquals(1, instances.size());
            new MeshDrawPlan(instances, 1, true).close();
        }
    }
}

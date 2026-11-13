package com.jme3.renderer.gpuscene;

import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.MorphTarget;
import com.jme3.scene.shape.Box;
import com.jme3.shader.VarType;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpuSceneEligibilityTest {
    private Geometry geometry() {
        MaterialDef definition = new MaterialDef(null, "Eligibility test");
        definition.addMaterialParam(VarType.Boolean, "UseInstancing", false);
        Geometry geometry = new Geometry("Rigid mesh", new Box(1, 1, 1));
        geometry.setMaterial(new Material(definition));
        geometry.updateGeometricState();
        return geometry;
    }

    @Test
    public void rigidOpaqueGeometryRemainsEligible() {
        assertTrue(GpuScenePipeline.supportsGeometry(geometry()));
    }

    @Test
    public void skeletalMeshIsExcludedBeforeAnimationUniformsExist() {
        Geometry geometry = geometry();
        geometry.getMesh().setBuffer(VertexBuffer.Type.HWBoneIndex, 4, new byte[geometry.getMesh().getVertexCount() * 4]);
        assertNull(geometry.getMaterial().getParam("NumberOfBones"));
        assertFalse(GpuScenePipeline.supportsGeometry(geometry));
    }

    @Test
    public void morphMeshIsExcludedBeforeAnimationUniformsExist() {
        Geometry geometry = geometry();
        geometry.getMesh().addMorphTarget(new MorphTarget("Shape"));
        assertNull(geometry.getMaterial().getParam("NumberOfMorphTargets"));
        assertFalse(GpuScenePipeline.supportsGeometry(geometry));
    }

    @Test
    public void transparentAndBlendedObjectsRemainTraditional() {
        Geometry geometry = geometry();
        geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
        assertFalse(GpuScenePipeline.supportsGeometry(geometry));
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        geometry.getMaterial().getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        assertFalse(GpuScenePipeline.supportsGeometry(geometry));
    }
    @Test
    public void explicitBatchOptOutRemainsTraditional() {
        Geometry geometry = geometry();
        geometry.setBatchHint(com.jme3.scene.Spatial.BatchHint.Never);
        assertFalse(GpuScenePipeline.supportsGeometry(geometry));
    }

    @Test
    public void legacyInstancedGeometryIsNotInstancedTwice() {
        Geometry source = geometry();
        com.jme3.scene.instancing.InstancedGeometry instanced =
                new com.jme3.scene.instancing.InstancedGeometry("Existing instances");
        instanced.setMesh(source.getMesh());
        instanced.setMaterial(source.getMaterial());
        assertFalse(GpuScenePipeline.supportsGeometry(instanced));
    }

    @Test
    public void accelerationCacheSwitchDoesNotInvalidateRenderedBounds() {
        com.jme3.scene.Node root = new com.jme3.scene.Node();
        root.attachChild(geometry());
        root.updateGeometricState();
        com.jme3.renderer.Camera camera = new com.jme3.renderer.Camera(640, 480);
        root.setIncrementalUpdatesEnabled(true);
        root.checkCulling(camera);
        root.setIncrementalUpdatesEnabled(false);
        root.checkCulling(camera);
    }
}

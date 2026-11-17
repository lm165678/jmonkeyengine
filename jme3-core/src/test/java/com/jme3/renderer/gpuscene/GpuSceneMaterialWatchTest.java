package com.jme3.renderer.gpuscene;

import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture2D;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for temporary editor GI bindings invalidating static mesh batches. */
public class GpuSceneMaterialWatchTest {
    private Material material() {
        MaterialDef definition = new MaterialDef(null, "Preview material");
        definition.addMaterialParam(VarType.Boolean, "GiEnabled", false);
        definition.addMaterialParam(VarType.Float, "GiBias", 0.05f);
        definition.addMaterialParam(VarType.Vector4, "BaseColor", ColorRGBA.White.clone());
        definition.addMaterialParam(VarType.FloatArray, "Weights", null);
        definition.addMaterialParamTexture(VarType.Texture2D, "GiIrradiance", null, null);
        return new Material(definition);
    }

    @Test
    public void repeatedGiRestoreAndRebindKeepsBatchResident() {
        Material material = material();
        Texture2D atlas = new Texture2D();
        material.setTexture("GiIrradiance", atlas);
        material.setBoolean("GiEnabled", true);
        GpuScenePipeline.MaterialWatch watch = new GpuScenePipeline.MaterialWatch(material);
        for (int frame = 0; frame < 1000; frame++) {
            material.clearParam("GiIrradiance");
            material.setBoolean("GiEnabled", false);
            material.setTexture("GiIrradiance", atlas);
            material.setBoolean("GiEnabled", true);
            material.setFloat("GiBias", 0.05f);
            assertFalse("Unchanged GI frame " + frame, watch.changed(material));
        }
        material.setTexture("GiIrradiance", new Texture2D());
        assertTrue("A replacement atlas must refresh proxy materials", watch.changed(material));
    }

    @Test
    public void actualMaterialEditsStillInvalidate() {
        Material material = material();
        GpuScenePipeline.MaterialWatch watch = new GpuScenePipeline.MaterialWatch(material);
        material.setFloat("GiBias", 0.1f);
        assertTrue(watch.changed(material));
        watch = new GpuScenePipeline.MaterialWatch(material);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        assertTrue(watch.changed(material));
        watch = new GpuScenePipeline.MaterialWatch(material);
        material.setTransparent(true);
        assertTrue(watch.changed(material));
    }

    @Test
    public void reusedMutableValuesAreComparedWithSnapshot() {
        Material material = material();
        ColorRGBA color = ColorRGBA.White.clone();
        material.setColor("BaseColor", color);
        GpuScenePipeline.MaterialWatch watch = new GpuScenePipeline.MaterialWatch(material);
        color.r = 0.2f;
        material.setColor("BaseColor", color);
        assertTrue(watch.changed(material));
        float[] values = {1f, 2f};
        material.setParam("Weights", VarType.FloatArray, values);
        watch = new GpuScenePipeline.MaterialWatch(material);
        material.setParam("Weights", VarType.FloatArray, values);
        assertFalse(watch.changed(material));
        values[0] = 3f;
        material.setParam("Weights", VarType.FloatArray, values);
        assertTrue(watch.changed(material));
    }

    @Test
    public void disablingGiMustInvalidate() {
        Material material = material();
        material.setBoolean("GiEnabled", true);
        GpuScenePipeline.MaterialWatch watch = new GpuScenePipeline.MaterialWatch(material);
        material.setBoolean("GiEnabled", false);
        assertTrue(watch.changed(material));
    }
}

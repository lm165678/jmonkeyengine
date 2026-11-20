package com.jme3.renderer.temporal;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.scene.*;
import com.jme3.util.BufferUtils;
import java.nio.FloatBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class TemporalPoseTest {
    @Test
    public void packedMorphAndHardwareSkinMatchModelPositions() {
        DesktopAssetManager assets = new DesktopAssetManager(true);
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{1, 2, 3});
        mesh.setBuffer(VertexBuffer.Type.MorphTarget0, 3, new float[]{2, 0, 0});
        mesh.setBuffer(VertexBuffer.Type.HWBoneIndex, 4, BufferUtils.createByteBuffer(new byte[]{0, 0, 0, 0}));
        mesh.setBuffer(VertexBuffer.Type.HWBoneWeight, 4, new float[]{1, 0, 0, 0});
        Geometry geometry = new Geometry("Animated", mesh);
        geometry.setMaterial(material);
        material.setParam("MorphWeights", com.jme3.shader.VarType.FloatArray, new float[]{.5f});
        material.setInt("NumberOfTargetsBuffers", 1);
        material.setInt("NumberOfMorphTargets", 1);
        material.setInt("NumberOfBones", 1);
        Matrix4f bone = new Matrix4f();
        bone.setTranslation(0, 4, 0);
        material.setParam("BoneMatrices", com.jme3.shader.VarType.Matrix4Array, new Matrix4f[]{bone});
        FloatBuffer first = TemporalScene.samplePositions(geometry);
        assertEquals(2, first.get(0), .00001f);
        assertEquals(6, first.get(1), .00001f);
        assertEquals(3, first.get(2), .00001f);
        bone.setTranslation(0, 5, 0);
        FloatBuffer second = TemporalScene.samplePositions(geometry);
        assertEquals(7, second.get(1), .00001f);
        assertEquals(6, first.get(1), .00001f);
        assertEquals(1, mesh.getFloatBuffer(VertexBuffer.Type.Position).get(0), .00001f);
        BufferUtils.destroyDirectBuffer(first);
        BufferUtils.destroyDirectBuffer(second);
    }
}

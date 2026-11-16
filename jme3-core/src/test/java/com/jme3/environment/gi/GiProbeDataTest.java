package com.jme3.environment.gi;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.texture.Texture;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiProbeDataTest {
    private GiProbeData data() {
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 3, 2, 2);
        float[][] sh = new float[12][27];
        float[][] moments = new float[12][8];
        for (int p = 0; p < 12; p++) {
            for (int c = 0; c < 27; c++) {
                sh[p][c] = p * 100 + c;
            }
            for (int c = 0; c < 8; c++) {
                moments[p][c] = p * 10 + c;
            }
        }
        return new GiProbeData(grid, 2, sh, moments);
    }

    @Test
    public void atlasSurvivesSerializationAndBindingsRestoreParameters() throws IOException {
        GiProbeData original = data();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(original, bytes);
        GiProbeData copy = (GiProbeData) BinaryImporter.getInstance().load(
                new ByteArrayInputStream(bytes.toByteArray()));
        DesktopAssetManager assets = new DesktopAssetManager(true);
        for (String definition : new String[]{"Light/PBRLighting", "Light/Lighting", "Misc/Unshaded"}) {
            Material material = new Material(assets, "Common/MatDefs/" + definition + ".j3md");
            material.setFloat("GiBias", 0.75f);
            GiProbeData.Binding binding = copy.bind(material, new Vector3f(10, 20, 30), 0.1f);
            assertEquals(new Vector3f(10, 20, 30), material.getParam("GiOrigin").getValue());
            Texture atlas = (Texture) material.getParam("GiIrradiance").getValue();
            // Probe 9 sits in tile (1,2) of a four-column atlas, coefficient 8.
            int pixel = (2 * 36 + 9 + 8) * 16;
            assertEquals(926f, atlas.getImage().getData(0).getFloat(pixel + 8), 0f);
            binding.close();
            binding.close();
            assertNull(material.getParam("GiIrradiance"));
            assertEquals(0.75f, (Float) material.getParam("GiBias").getValue(), 0f);
            assertEquals(false, material.getParam("GiEnabled").getValue());
        }
    }

    @Test
    public void cloneEditsDoNotChangeOriginalVolume() {
        GiProbeVolume original = new GiProbeVolume();
        original.publish(data());
        GiProbeVolume copy = (GiProbeVolume) original.clone();
        assertSame(original.getData(), copy.getData());
        copy.configure(new Vector3f(4, 5, 6), 3, 4, 5);
        assertEquals(new Vector3f(5, 5, 5), original.getProbeCounts());
        assertEquals(new Vector3f(16, 16, 16), original.getCoverage());
        assertNull(copy.getData());
        assertNotNull(original.getData());
    }
}

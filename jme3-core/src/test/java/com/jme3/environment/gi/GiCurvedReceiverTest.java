package com.jme3.environment.gi;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiCurvedReceiverTest {
    @Test
    public void pageToggleOnlyChangesDescriptorAndRestoresOldFlags() {
        com.jme3.renderer.Renderer renderer = (com.jme3.renderer.Renderer)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class[]{com.jme3.renderer.Renderer.class}, (proxy, method, args) -> null);
        float[][] sh = new float[8][27];
        float[][] moments = new float[8][8];
        GiProbeData data = new GiProbeData(new GiProbeGrid(com.jme3.math.Vector3f.ZERO,
                com.jme3.math.Vector3f.UNIT_XYZ, 2, 2, 2), 2, sh, moments);
        try (GiPagePool pool = new GiPagePool(renderer, 64, 150000)) {
            assertTrue(pool.offer("room", data, com.jme3.math.Vector3f.ZERO, 1));
            pool.upload(100000, Long.MAX_VALUE);
            com.jme3.material.Material material = new com.jme3.material.Material(
                    new com.jme3.asset.DesktopAssetManager(true), "Common/MatDefs/Light/PBRLighting.j3md");
            try (GiProbeSet.Binding binding = pool.bindings().bind(material, 0)) {
                java.nio.ByteBuffer metadata = material.getTextureParam("GiVolumeData")
                        .getTextureValue().getImage().getData(0);
                float oldFlags = metadata.getFloat(60);
                long oldUploads = pool.uploadedBytes();
                pool.appearance("room", 1, 0, false, false, false, true);
                assertEquals(oldFlags + 0.0078125f, metadata.getFloat(60), 0);
                pool.appearance("room", 1, 0, false, false, false, false);
                assertEquals(oldFlags, metadata.getFloat(60), 0);
                // Each toggle uploads one 64-byte descriptor, not the baked textures.
                assertEquals(oldUploads + 128, pool.uploadedBytes());
            }
        }
    }

    @Test
    public void defaultsOffAndPersistsWithRefinementChildren() throws Exception {
        GiProbeVolume parent = new GiProbeVolume();
        GiProbeVolume child = new GiProbeVolume();
        parent.attachChild(child);
        assertFalse(parent.isCurvedReceiverCorrection());
        assertFalse(child.isCurvedReceiverCorrection());
        parent.setCurvedReceiverCorrection(true);
        assertTrue(child.isCurvedReceiverCorrection());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BinaryExporter().save(parent, out);
        GiProbeVolume loaded = (GiProbeVolume) new BinaryImporter()
                .load(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(loaded.isCurvedReceiverCorrection());
        assertTrue(((GiProbeVolume) loaded.getChild(0)).isCurvedReceiverCorrection());
        loaded.setCurvedReceiverCorrection(false);
        assertFalse(((GiProbeVolume) loaded.getChild(0)).isCurvedReceiverCorrection());
    }
}

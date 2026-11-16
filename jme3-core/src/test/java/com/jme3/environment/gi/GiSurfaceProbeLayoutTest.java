package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiSurfaceProbeLayoutTest {
    @Test
    public void surfacePositionsHaveClearanceAndAreNotLatticePoints() {
        Geometry box = new Geometry("box", new Box(0.7f, 0.7f, 0.7f));
        box.setLocalTranslation(2, 2, 2);
        box.updateGeometricState();
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 5, 5, 5);
        Vector3f[] points = GiSurfaceProbeLayout.plan(box, grid, Vector3f.ZERO, 0.1f, 0);
        GiProbeSolidClassifier classifier = new GiProbeSolidClassifier(box);
        int valid = 0;
        for (Vector3f point : points) {
            if (point == null) {
                continue;
            }
            valid++;
            assertFalse(classifier.inside(point));
            Vector3f delta = point.subtract(2, 2, 2);
            float outside = Math.max(Math.abs(delta.x), Math.max(Math.abs(delta.y), Math.abs(delta.z))) - 0.7f;
            assertTrue("Surface clearance", outside >= 0.099f);
            assertTrue("Remains near geometry", outside < 0.25f);
        }
        assertTrue(valid > 6);
        assertTrue(valid < grid.getProbeCount());
    }

    @Test
    public void thinWallKeepsSeparateProbesOnBothSides() {
        Geometry wall = new Geometry("thin", new Box(0.02f, 0.8f, 0.8f));
        wall.setLocalTranslation(2, 2, 2);
        wall.updateGeometricState();
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 5, 5, 5);
        Vector3f[] points = GiSurfaceProbeLayout.plan(wall, grid, Vector3f.ZERO, 0.03f, 0);
        boolean left = false;
        boolean right = false;
        for (Vector3f point : points) {
            if (point == null || Math.abs(point.y - 2) > 0.5f || Math.abs(point.z - 2) > 0.5f) {
                continue;
            }
            left |= point.x < 1.98f;
            right |= point.x > 2.02f;
        }
        assertTrue("Both sides must survive separation filtering", left && right);
    }

    @Test
    public void freeOffsetsAndModeSurviveJ3oAndCompressedPayload() throws Exception {
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 3, 3, 3);
        float[][] sh = new float[27][27];
        float[][] moments = new float[27][8];
        Vector3f[] offsets = new Vector3f[27];
        boolean[] valid = new boolean[27];
        valid[0] = true;
        offsets[0] = new Vector3f(1.3f, 1.2f, 1.1f);
        GiProbeData data = new GiProbeData(grid, 2, sh, moments, offsets, valid, false, 10);
        data.enableSurfaceLayout();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        data.saveCompressed(bytes);
        GiProbeData decoded = GiProbeData.loadCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(decoded.isSurfaceLayout());
        assertEquals(offsets[0], decoded.getProbeOffset(0, new Vector3f()));
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(2, 2, 2), 3, 3, 3);
        volume.setSurfaceLayout(true);
        volume.updateGeometricState();
        volume.publish(decoded);
        bytes.reset();
        BinaryExporter.getInstance().save(volume, bytes);
        GiProbeVolume restored = (GiProbeVolume) BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
        restored.updateGeometricState();
        assertTrue(restored.isSurfaceLayout());
        assertTrue(restored.getData().isSurfaceLayout());
        restored.setSurfaceLayout(false);
        assertNull(restored.getData());
        assertFalse(new GiProbeVolume().isSurfaceLayout());
    }
}

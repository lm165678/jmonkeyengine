package com.jme3.environment.gi;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiProbeVolumeTransformTest {
    @Test
    public void positiveParentScaleChangesSpacingWithoutChangingCounts() {
        Node root = new Node();
        root.setLocalScale(2, 3, 4);
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(4, 6, 8), 3, 4, 5);
        volume.setLocalScale(0.5f, 2, 1);
        root.attachChild(volume);
        root.updateGeometricState();
        assertEquals(new Vector3f(2, 12, 8), volume.getBakeGrid().getSpacing());
        assertEquals(60, volume.getBakeGrid().getProbeCount());
        assertEquals(new Vector3f(4, 6, 8), volume.getCoverage());
    }

    @Test
    public void movingOrScalingInvalidatesPreviewUntilUndoAndSurvivesSave() throws Exception {
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(2, 2, 2), 2, 2, 2);
        volume.setLocalScale(2, 1, 3);
        volume.updateGeometricState();
        GiProbeData data = new GiProbeData(volume.getBakeGrid(), 2, new float[8][27], new float[8][8]);
        volume.publish(data);
        assertSame(data, volume.getData());
        volume.move(1, 0, 0);
        assertNull(volume.getData());
        volume.setLocalTranslation(Vector3f.ZERO);
        assertSame(data, volume.getData());
        volume.setLocalScale(1);
        assertNull(volume.getData());
        volume.setLocalScale(2, 1, 3);
        assertSame(data, volume.getData());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(volume, output);
        GiProbeVolume loaded = (GiProbeVolume) BinaryImporter.getInstance().load(
                new ByteArrayInputStream(output.toByteArray()));
        assertNotNull(loaded.getData());
        assertEquals(new Vector3f(4, 2, 6), loaded.getData().getGrid().getSpacing());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rotatedParentIsRejected() {
        Node root = new Node();
        GiProbeVolume volume = new GiProbeVolume();
        root.attachChild(volume);
        root.setLocalRotation(new Quaternion().fromAngleAxis(0.5f, Vector3f.UNIT_Y));
        root.updateGeometricState();
        volume.getBakeGrid();
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroScaleIsRejected() {
        GiProbeVolume volume = new GiProbeVolume();
        volume.setLocalScale(0);
        volume.getBakeGrid();
    }
}

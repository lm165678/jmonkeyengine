package com.jme3.scene.plugins.sdf;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SdfGeneratorTest {
    private SdfSettings settings(SdfSettings.SignMode mode) {
        return new SdfSettings(17, 17, 17, .2f, mode);
    }

    private Geometry box() {
        return new Geometry("Box", new Box(1, 1, 1));
    }

    private static float boxDistance(Vector3f p, Vector3f halfSize) {
        double x = Math.abs(p.x) - halfSize.x;
        double y = Math.abs(p.y) - halfSize.y;
        double z = Math.abs(p.z) - halfSize.z;
        double outside = Math.sqrt(Math.pow(Math.max(x, 0), 2) + Math.pow(Math.max(y, 0), 2)
                + Math.pow(Math.max(z, 0), 2));
        return (float) (outside + Math.min(Math.max(x, Math.max(y, z)), 0));
    }

    @Test
    public void rotatedMirroredGeometryKeepsOrientationIndependentSigns() {
        Node root = new Node();
        Geometry shape = new Geometry("Rotated", new Box(2, 1, .5f));
        shape.setLocalScale(-1, 1, 1);
        shape.rotate(0, 0, com.jme3.math.FastMath.HALF_PI);
        root.attachChild(shape);
        SdfVolume sdf = SdfGenerator.generate(root, settings(SdfSettings.SignMode.EVEN_ODD), null);
        assertEquals(-.5f, sdf.sample(Vector3f.ZERO), 1e-5f);
        assertEquals(.3f, sdf.sample(new Vector3f(1.3f, 0, 0)), 2e-2f);
        assertEquals(1.8f, sdf.getMax().x, 1e-5f);
        assertEquals(2.8f, sdf.getMax().y, 1e-5f);
    }

    @Test
    public void nestedClosedShellsRepresentACavity() {
        Node root = new Node();
        root.attachChild(new Geometry("Outer", new Box(2, 2, 2)));
        root.attachChild(box());
        SdfVolume sdf = SdfGenerator.generate(root, settings(SdfSettings.SignMode.EVEN_ODD), null);
        assertEquals(1, sdf.sample(Vector3f.ZERO), 1e-6f);
        assertTrue(sdf.sample(new Vector3f(1.5f, 0, 0)) < 0);
    }

    @Test
    public void interruptionCancelsWithoutClearingTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            SdfGenerator.generate(box(), settings(SdfSettings.SignMode.UNSIGNED), null);
            fail("Expected interruption cancellation");
        } catch (CancellationException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void triangleStripAndFanCanBeCaptured() {
        for (Mesh.Mode mode : new Mesh.Mode[] {Mesh.Mode.TriangleStrip, Mesh.Mode.TriangleFan}) {
            Mesh mesh = new Mesh();
            mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0});
            mesh.setMode(mode);
            SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(new Geometry("Strip", mesh));
            assertEquals(2, snapshot.getTriangleCount());
        }
    }

    @Test
    public void closedBoxMatchesAnalyticDistanceAtEverySample() {
        SdfVolume sdf = SdfGenerator.generate(box(), settings(SdfSettings.SignMode.EVEN_ODD), null);
        Vector3f min = sdf.getMin();
        Vector3f max = sdf.getMax();
        for (int z = 0; z < 17; z++) {
            for (int y = 0; y < 17; y++) {
                for (int x = 0; x < 17; x++) {
                    Vector3f p = new Vector3f(min.x + (max.x - min.x) * x / 16,
                            min.y + (max.y - min.y) * y / 16, min.z + (max.z - min.z) * z / 16);
                    assertEquals(boxDistance(p, Vector3f.UNIT_XYZ), sdf.getDistance(x, y, z), 2e-6f);
                }
            }
        }
        assertEquals(-1, sdf.sample(Vector3f.ZERO), 1e-6f);
    }

    @Test
    public void childTransformsApplyButRootPlacementDoesNot() {
        Node root = new Node();
        root.setLocalTranslation(100, 200, 300);
        root.setLocalScale(9);
        Node child = new Node();
        child.setLocalScale(2);
        root.attachChild(child);
        Geometry shape = box();
        shape.setLocalTranslation(1, 0, 0);
        shape.setLocalScale(1, .5f, .25f);
        child.attachChild(shape);
        SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(root);
        // Later scene mutations must not change an already captured snapshot.
        shape.setLocalTranslation(500, 0, 0);
        SdfVolume sdf = SdfGenerator.generate(snapshot, settings(SdfSettings.SignMode.EVEN_ODD), null);
        assertEquals(-.5f, sdf.sample(new Vector3f(2, 0, 0)), 1e-6f);
        assertEquals(-.8f, sdf.getMin().x, 1e-5f);
        assertEquals(4.8f, sdf.getMax().x, 1e-5f);
    }

    @Test
    public void unsignedOpenSurfaceAndConservativeSteps() {
        SdfVolume plane = SdfGenerator.generate(new Geometry("Plane", new Quad(2, 2)),
                settings(SdfSettings.SignMode.UNSIGNED), null);
        assertEquals(.1f, plane.sample(new Vector3f(1, 1, .1f)), 1e-6f);
        SdfVolume sdf = SdfGenerator.generate(box(), settings(SdfSettings.SignMode.UNSIGNED), null);
        Random random = new Random(1234);
        for (int i = 0; i < 1000; i++) {
            Vector3f p = new Vector3f(random.nextFloat() * 2.8f - 1.4f,
                    random.nextFloat() * 2.8f - 1.4f, random.nextFloat() * 2.8f - 1.4f);
            float step = sdf.sampleConservativeDistance(p);
            assertTrue(step >= 0);
            assertTrue(step <= Math.abs(boxDistance(p, Vector3f.UNIT_XYZ)) + 1e-6f);
        }
    }

    @Test
    public void savableRoundTripPreservesModelMetadataAndSamples() throws Exception {
        Geometry model = box();
        SdfVolume sdf = SdfGenerator.generate(model, settings(SdfSettings.SignMode.EVEN_ODD), null);
        sdf.attachTo(model);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(model, bytes);
        Spatial loaded = (Spatial) new BinaryImporter().load(bytes.toByteArray());
        SdfVolume restored = loaded.getUserData(SdfVolume.USER_DATA_KEY);
        assertNotNull(restored);
        assertArrayEquals(sdf.copyDistances(), restored.copyDistances(), 0);
        assertEquals(sdf.getMin(), restored.getMin());
        assertEquals(sdf.getMax(), restored.getMax());
        assertEquals(sdf.getSignMode(), restored.getSignMode());
        assertEquals(model.getTriangleCount(), loaded.getTriangleCount());
        float[] copy = restored.copyDistances();
        copy[0] = -999;
        assertNotEquals(-999f, restored.getDistance(0, 0, 0), 0);
        assertEquals(17, restored.toTexture3D().getImage().getDepth());
    }

    @Test
    public void cancellationDoesNotModifyExistingModelData() {
        Geometry model = box();
        model.setUserData("marker", 42);
        try {
            SdfGenerator.generate(model, settings(SdfSettings.SignMode.EVEN_ODD), progress -> progress < .2f);
            fail("Expected cancellation");
        } catch (CancellationException expected) {
            assertEquals(Integer.valueOf(42), model.getUserData("marker"));
            assertNull(model.getUserData(SdfVolume.USER_DATA_KEY));
        }
    }

    @Test
    public void progressIsMonotonicAndComplete() {
        List<Float> values = new ArrayList<>();
        SdfGenerator.generate(box(), settings(SdfSettings.SignMode.UNSIGNED), p -> {
            values.add(p);
            return true;
        });
        assertEquals(0, values.get(0), 0);
        assertEquals(1, values.get(values.size() - 1), 0);
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i) >= values.get(i - 1));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyMeshCannotProduceAValidField() {
        SdfMeshSnapshot.capture(new Node());
    }

    @Test(expected = IllegalArgumentException.class)
    public void gridSizeIsCheckedBeforeAllocating() {
        new SdfSettings(Integer.MAX_VALUE, 2, 2, .1f, SdfSettings.SignMode.UNSIGNED);
    }

    @Test
    public void degenerateFacesAreSkippedWithoutNaNs() {
        Node root = new Node();
        root.attachChild(box());
        Mesh degenerate = new Mesh();
        degenerate.setBuffer(VertexBuffer.Type.Position, 3, new float[9]);
        root.attachChild(new Geometry("Degenerate", degenerate));
        SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(root);
        assertEquals(12, snapshot.getTriangleCount());
        assertEquals(1, snapshot.getIgnoredDegenerateTriangles());
        SdfVolume sdf = SdfGenerator.generate(snapshot, settings(SdfSettings.SignMode.EVEN_ODD), null);
        assertEquals(-1, sdf.sample(Vector3f.ZERO), 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void outsideSampleIsNotSilentlyClamped() {
        SdfVolume sdf = SdfGenerator.generate(box(), settings(SdfSettings.SignMode.UNSIGNED), null);
        sdf.sample(new Vector3f(100, 0, 0));
    }
}

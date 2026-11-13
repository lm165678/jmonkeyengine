package com.jme3.scene.plugins.sdf;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class SdfBakeDataTest {
    private SdfBakeData bake(Node model) {
        return SdfBakeData.bake(model, new SdfSettings(9, 9, 9, .1f, SdfSettings.SignMode.EVEN_ODD), "v1", null);
    }

    private Node model() {
        Node root = new Node("Model");
        root.attachChild(new Geometry("Box", new Box(1, 1, 1)));
        return root;
    }

    @Test
    public void rootPlacementDoesNotInvalidateButChildTransformDoes() {
        Node model = model();
        SdfBakeData data = bake(model);
        model.setLocalTranslation(3, 4, 5);
        model.setLocalScale(2);
        assertEquals(SdfBakeData.Status.VALID, data.validate(model, "v1"));
        model.getChild(0).move(1, 0, 0);
        assertEquals(SdfBakeData.Status.GEOMETRY_CHANGED, data.validate(model, "v1"));
    }

    @Test
    public void externalDependencyAndExplicitInvalidationAreSeparate() {
        Node model = model();
        SdfBakeData data = bake(model);
        SdfVolume original = data.getVolume();
        assertEquals(SdfBakeData.Status.MATERIAL_CHANGED, data.validate(model, "v2"));
        data.refreshMaterials(model, "v2");
        assertSame(original, data.getVolume());
        assertEquals(SdfBakeData.Status.VALID, data.validate(model, "v2"));
        data.invalidate();
        assertEquals(SdfBakeData.Status.INVALIDATED, data.validate(model, "v2"));
    }

    @Test
    public void bakeDataSurvivesModelRoundTripAndResolvesGeometry() throws Exception {
        Node model = model();
        SdfBakeData data = bake(model);
        data.attachTo(model);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(model, bytes);
        Node loaded = (Node) BinaryImporter.getInstance().load(bytes.toByteArray());
        SdfBakeData restored = loaded.getUserData(SdfBakeData.USER_DATA_KEY);
        assertEquals(SdfBakeData.Status.VALID, restored.validate(loaded, "v1"));
        assertSame(loaded.getChild(0), restored.getLayout().resolve(loaded, 0));
        assertArrayEquals(data.getVolume().copyDistances(), restored.getVolume().copyDistances(), 0);
        assertEquals(6, restored.getLayout().getCardCount());
    }

    @Test
    public void everyCardProjectsItsFacingSurfaceAndRejectsBehindByDepth() {
        SurfaceCacheLayout layout = SurfaceCacheLayout.bake(model());
        for (int face = 0; face < 6; face++) {
            Vector3f surface = new Vector3f();
            surface.set(face / 2, face % 2 == 0 ? 1 : -1);
            com.jme3.renderer.Camera camera = layout.createCamera(face, 128);
            Vector3f pixel = camera.getScreenCoordinates(surface);
            assertEquals(64, pixel.x, .001f);
            assertEquals(64, pixel.y, .001f);
            assertTrue(pixel.z > 0 && pixel.z < .1f);
            assertTrue(camera.getScreenCoordinates(surface.negate()).z > .9f);
        }
    }

    @Test
    public void hierarchyChangesInvalidateEvenWhenMeshIsShared() {
        Node model = model();
        SdfBakeData data = bake(model);
        model.attachChild(model.getChild(0).clone());
        assertEquals(SdfBakeData.Status.GEOMETRY_CHANGED, data.validate(model, "v1"));
    }

    @Test
    public void candidateMappingIncludesDescendantTransformAndRejectsOutside() {
        Node model = model();
        model.getChild(0).move(5, 0, 0);
        SurfaceCacheLayout layout = SurfaceCacheLayout.bake(model);
        assertArrayEquals(new int[]{0}, layout.findCandidates(new Vector3f(5, 0, 1), 0));
        assertEquals(0, layout.findCandidates(Vector3f.ZERO, 0).length);
        Vector3f uvDepth = layout.project(4, new Vector3f(5, 0, 1));
        assertEquals(.5f, uvDepth.x, 1e-5f);
        assertEquals(.5f, uvDepth.y, 1e-5f);
        assertTrue(uvDepth.z < .1f);
    }

    @Test
    public void captureBudgetAndClosedStateAreValidatedWithoutGpu() {
        Node model = model();
        SurfaceAttributeCache cache = new SurfaceAttributeCache(model, bake(model), "v1", 32, 2);
        assertEquals(0, cache.getResidentCount());
        cache.close();
        cache.close();
        try {
            cache.getResident(0);
            fail("Closed cache accepted a lookup");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }
}

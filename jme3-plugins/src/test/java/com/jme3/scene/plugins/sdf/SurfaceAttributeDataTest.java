package com.jme3.scene.plugins.sdf;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceAttributeDataTest {
    private SurfaceBakeData surface(Node model) {
        SurfaceBakeData result = SurfaceBakeData.prepare(model, "v1");
        result.setTriangleHash(SdfMeshSnapshot.capture(model).geometryHash());
        float[][] cards = new float[result.getLayout().getCardCount()][8 * 8 * 13];
        for (float[] card : cards) {
            card[0] = 0.25f;
            card[8 * 8 * 4] = -1;
            card[8 * 8 * 8] = 12.5f;
            Arrays.fill(card, 8 * 8 * 12, card.length, 1);
        }
        result.complete(new SurfaceAttributeData(8, cards));
        return result;
    }

    private Node model() {
        Node model = new Node();
        model.attachChild(new Geometry("source", new Box(1, 1, 1)));
        return model;
    }

    @Test
    public void surfaceOnlyPersistsHdrSignedNormalAndDepthWithoutSdf() throws Exception {
        Node model = model();
        SurfaceBakeData surface = surface(model);
        surface.attachTo(model);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(model, bytes);
        Node loaded = (Node) BinaryImporter.getInstance().load(bytes.toByteArray());
        assertNull(loaded.getUserData(SdfVolume.USER_DATA_KEY));
        SurfaceBakeData restored = loaded.getUserData(SurfaceBakeData.USER_DATA_KEY);
        assertEquals(SurfaceBakeData.Status.VALID, restored.validate(loaded, "v1"));
        assertArrayEquals(surface.getAttributes().copyCardData(0), restored.getAttributes().copyCardData(0), 0);
        try (SurfaceAttributeData.Textures textures = restored.getAttributes().uploadAll()) {
            assertEquals(-1, textures.getTexture(0, SurfaceAttributeCache.Channel.NORMAL).getImage().getData(0).asFloatBuffer().get(0), 0);
            assertEquals(12.5f, textures.getTexture(0, SurfaceAttributeCache.Channel.EMISSION).getImage().getData(0).asFloatBuffer().get(0), 0);
            assertEquals(1, textures.getDepthTexture(0).getImage().getData(0).asFloatBuffer().get(0), 0);
        }
    }

    @Test
    public void independentBakesMatchAcrossPlacementButNotGeometryChange() {
        Node model = model();
        SdfVolume sdf = SdfGenerator.bake(model, new SdfSettings(9, 9, 9, .1f, SdfSettings.SignMode.EVEN_ODD), null);
        assertNull(model.getUserData(SurfaceBakeData.USER_DATA_KEY));
        SurfaceBakeData surface = surface(model);
        assertTrue(surface.isCompatibleWith(sdf));
        model.move(100, 200, 300);
        assertEquals(SurfaceBakeData.Status.VALID, surface.validate(model, "v1"));
        assertTrue(surface(model).isCompatibleWith(sdf));
        model.getChild(0).move(2, 0, 0);
        assertFalse(surface(model).isCompatibleWith(sdf));
        assertEquals(SurfaceBakeData.Status.GEOMETRY_CHANGED, surface.validate(model, "v1"));
    }

    @Test
    public void payloadBudgetFailsBeforeTouchingRenderer() {
        try {
            SurfaceAttributeBaker.bake(model(), "v1", null, null, 128, 16, null);
            fail("Budget ignored");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("budget"));
        }
    }

    @Test
    public void invalidDepthAndNonFinitePixelsAreRejected() {
        float[][] invalid = new float[1][8 * 8 * 13];
        invalid[0][8 * 8 * 12] = 2;
        try {
            new SurfaceAttributeData(8, invalid);
            fail("Invalid depth accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sample"));
        }
    }
}

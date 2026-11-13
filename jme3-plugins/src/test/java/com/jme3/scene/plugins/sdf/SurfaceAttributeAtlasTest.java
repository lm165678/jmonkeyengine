package com.jme3.scene.plugins.sdf;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceAttributeAtlasTest {
    @Test
    public void exactRectanglesFitSix64PixelCardsIn256Atlas() {
        Node root = new Node();
        Geometry asset = model(64, .5f);
        root.attachChild(asset);
        root.updateGeometricState();
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(256, 10, 10)) {
            atlas.update(root, Vector3f.ZERO);
            assertEquals(6, atlas.getResidentCardCount());
            assertEquals(0, atlas.getMissingCardCount());
        }
    }

    @Test
    public void rendererUploadsRegionsWithByteBudgetAndRestoresAfterContextReset() {
        Node root = new Node();
        root.attachChild(model(8, .5f));
        root.updateGeometricState();
        int[] calls = new int[2];
        boolean[] fallback = {false};
        com.jme3.renderer.Renderer renderer = (com.jme3.renderer.Renderer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.jme3.renderer.Renderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setTexture")) {
                        com.jme3.texture.Image image = ((com.jme3.texture.Texture) args[1]).getImage();
                        if (image.getId() < 0) {
                            image.setId(1);
                        }
                        image.clearUpdateNeeded();
                        calls[0]++;
                    } else if (method.getName().equals("modifyTexture")) {
                        if (fallback[0]) {
                            throw new UnsupportedOperationException();
                        }
                        assertEquals(10, args[6]);
                        assertEquals(10, args[7]);
                        calls[1]++;
                    }
                    return null;
                });
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(128, 10, 8)) {
            atlas.setUploadByteBudget(2800);
            atlas.update(root, Vector3f.ZERO, renderer);
            assertEquals(1, atlas.getUploadedCardCount());
            assertEquals(atlas.getTextureBytes(), atlas.getLastTransferBytes());
            atlas.update(root, Vector3f.ZERO, renderer);
            assertEquals(4, calls[1]);
            assertEquals(2800, atlas.getLastTransferBytes());
            atlas.getDepthTexture().getImage().resetObject();
            atlas.update(root, Vector3f.ZERO, renderer);
            assertEquals(2400 + 128 * 128 * 4, atlas.getLastTransferBytes());
            fallback[0] = true;
            atlas.update(root, Vector3f.ZERO, renderer);
            assertEquals(atlas.getTextureBytes(), atlas.getLastTransferBytes());
        }
    }

    @Test
    public void retentionMarginAvoidsBoundaryChurn() {
        Node root = new Node();
        Geometry asset = model(8, .5f);
        root.attachChild(asset);
        root.updateGeometricState();
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(128, 10, 8)) {
            atlas.setRetentionMargin(2);
            atlas.update(root, Vector3f.ZERO);
            SurfaceAttributeAtlas.Region token = atlas.getRegion(asset, 0);
            atlas.update(root, new Vector3f(12, 0, 0));
            assertSame(token, atlas.getRegion(asset, 0));
            assertEquals(0, atlas.getEvictedCardCount());
            atlas.update(root, new Vector3f(14, 0, 0));
            assertFalse(token.isResident());
            atlas.update(root, new Vector3f(12, 0, 0));
            assertNull(atlas.getRegion(asset, 0));
        }
    }

    private Geometry model(int resolution, float red) {
        Geometry model = new Geometry("Asset", new Box(1, 1, 1));
        SurfaceBakeData data = SurfaceBakeData.prepare(model, "test");
        float[][] cards = new float[6][resolution * resolution * 13];
        int count = resolution * resolution;
        for (float[] card : cards) {
            for (int i = 0; i < count; i++) {
                card[i * 4] = red;
                card[i * 4 + 3] = 1;
                card[count * 4 + i * 4] = -1;
                card[count * 8 + i * 4] = 12.5f;
                card[count * 12 + i] = .25f;
            }
        }
        data.complete(new SurfaceAttributeData(resolution, cards));
        data.attachTo(model);
        return model;
    }

    @Test
    public void mixedSizesShareAcrossInstancesAndPreservePixels() {
        Node root = new Node();
        Geometry small = model(8, .5f);
        Geometry large = model(16, .75f);
        Geometry copy = small.clone(false);
        copy.setUserData(SurfaceBakeData.USER_DATA_KEY, small.getUserData(SurfaceBakeData.USER_DATA_KEY));
        copy.setLocalTranslation(3, 0, 0);
        root.attachChild(small);
        root.attachChild(large);
        root.attachChild(copy);
        root.updateGeometricState();
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(128, 20, 20)) {
            atlas.update(root, Vector3f.ZERO);
            assertEquals(12, atlas.getResidentCardCount());
            assertSame(atlas.getRegion(small, 0), atlas.getRegion(copy, 0));
            assertEquals(8, atlas.getRegion(small, 0).getResolution());
            assertEquals(16, atlas.getRegion(large, 0).getResolution());
            Vector4f uv = atlas.getRegion(large, 0).getUvTransform();
            int pixel = Math.round(uv.x * 128) + 128 * Math.round(uv.y * 128);
            ByteBuffer diffuse = atlas.getTexture(SurfaceAttributeCache.Channel.DIFFUSE).getImage().getData(0);
            assertEquals(.75f, FastMath.convertHalfToFloat(diffuse.getShort(pixel * 8)), 0);
            assertEquals(.75f, FastMath.convertHalfToFloat(diffuse.getShort((pixel - 1) * 8)), 0);
            assertEquals(-1, FastMath.convertHalfToFloat(atlas.getTexture(SurfaceAttributeCache.Channel.NORMAL)
                    .getImage().getData(0).getShort(pixel * 8)), 0);
            assertEquals(12.5f, FastMath.convertHalfToFloat(atlas.getTexture(SurfaceAttributeCache.Channel.EMISSION)
                    .getImage().getData(0).getShort(pixel * 8)), 0);
            assertEquals(.25f, atlas.getDepthTexture().getImage().getData(0).getFloat(pixel * 4), 0);
            assertTrue(atlas.getWorldToCard(small, 0).mult(Vector3f.ZERO).distance(
                    atlas.getWorldToCard(copy, 0).mult(new Vector3f(3, 0, 0))) < 1e-5f);
            atlas.update(root, Vector3f.ZERO);
            assertEquals(0, atlas.getUploadedCardCount());
            assertEquals(0, atlas.getEvictedCardCount());
        }
    }

    @Test
    public void budgetEvictionCoalescingAndInvalidationAreExplicit() {
        Node root = new Node();
        Geometry first = model(14, .5f);
        Geometry second = model(30, .75f);
        second.setLocalTranslation(100, 0, 0);
        root.attachChild(first);
        root.attachChild(second);
        root.updateGeometricState();
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(32, 10, 2)) {
            atlas.update(root, Vector3f.ZERO);
            assertEquals(2, atlas.getUploadedCardCount());
            SurfaceAttributeAtlas.Region old = atlas.getRegion(first, 0);
            atlas.update(root, Vector3f.ZERO);
            assertEquals(4, atlas.getResidentCardCount());
            atlas.update(root, Vector3f.ZERO);
            assertEquals(0, atlas.getUploadedCardCount());
            assertEquals(2, atlas.getMissingCardCount());
            atlas.update(root, new Vector3f(100, 0, 0));
            assertFalse(old.isResident());
            assertEquals(1, atlas.getResidentCardCount());
            assertNotNull(atlas.getRegion(second, 0));
            assertNull(atlas.getRegion(first, 0));
            assertNull(atlas.getRegion(second, 1));
            SurfaceBakeData data = second.getUserData(SurfaceBakeData.USER_DATA_KEY);
            data.invalidate();
            assertNull(atlas.getRegion(second, 0));
            atlas.update(root, new Vector3f(100, 0, 0));
            assertEquals(0, atlas.getResidentCardCount());
        }
    }

    @Test
    public void nearerCardsReplaceFartherCardsWhenBothAreInRange() {
        Node root = new Node();
        Geometry a = model(14, .1f);
        Geometry b = model(14, .9f);
        b.setLocalTranslation(8, 0, 0);
        root.attachChild(a);
        root.attachChild(b);
        root.updateGeometricState();
        try (SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(32, 20, 8)) {
            atlas.update(root, Vector3f.ZERO);
            assertNotNull(atlas.getRegion(a, 0));
            assertNull(atlas.getRegion(b, 0));
            atlas.update(root, new Vector3f(8, 0, 0));
            assertNull(atlas.getRegion(a, 0));
            assertNotNull(atlas.getRegion(b, 0));
            assertEquals(4, atlas.getEvictedCardCount());
        }
    }

    @Test
    public void oversizedAssetsAndClosedTokensNeverExposeStaleSlots() {
        Node root = new Node();
        Geometry large = model(32, 1);
        Geometry small = model(8, .2f);
        root.attachChild(large);
        root.attachChild(small);
        root.updateGeometricState();
        SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(32, 20, 8);
        atlas.update(root, Vector3f.ZERO);
        assertNull(atlas.getRegion(large, 0));
        SurfaceAttributeAtlas.Region region = atlas.getRegion(small, 0);
        assertNotNull(region);
        atlas.close();
        atlas.close();
        assertFalse(region.isResident());
        try {
            region.getUvTransform();
            fail("Closed region exposed its reused rectangle");
        } catch (IllegalStateException expected) {
            // A stale token must not provide a usable sampling address.
        }
    }
}

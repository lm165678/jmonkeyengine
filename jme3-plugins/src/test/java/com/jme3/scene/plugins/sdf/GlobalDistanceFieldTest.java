package com.jme3.scene.plugins.sdf;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlobalDistanceFieldTest {
    @Test
    public void compositionPreservesSourceSurfaceInsteadOfSubtractingSafetyMargins() {
        Node root = new Node();
        root.attachChild(bakedBox());
        root.updateGeometricState();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO);
            // World x=0, 1, 1.5: center, real box surface, outside source bounds.
            // Subtracting an interpolation margin would inflate the zero surface.
            assertEquals(-1, field.getDistance(0, 12, 12, 12), 1e-5f);
            assertEquals(0, field.getDistance(0, 14, 12, 12), 1e-5f);
            assertEquals(.5f, field.getDistance(0, 15, 12, 12), 1e-5f);
        }
    }

    @Test
    public void regionalUploadsMatchCpuAndFreshCompositionIncludingFallbackAndReset() {
        Node root = new Node();
        Geometry box = bakedBox();
        root.attachChild(box);
        root.updateGeometricState();
        java.util.Map<com.jme3.texture.Image, float[]> gpu = new java.util.IdentityHashMap<>();
        boolean[] unsupported = {false};
        int[] regionCalls = {0};
        com.jme3.renderer.Renderer renderer = (com.jme3.renderer.Renderer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.jme3.renderer.Renderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setTexture")) {
                        com.jme3.texture.Image image = ((com.jme3.texture.Texture) args[1]).getImage();
                        float[] pixels = new float[image.getWidth() * image.getHeight() * image.getDepth()];
                        image.getData(0).asFloatBuffer().get(pixels);
                        gpu.put(image, pixels);
                        if (image.getId() < 0) {
                            image.setId(1);
                        }
                        image.clearUpdateNeeded();
                    } else if (method.getName().equals("updateTexture3DRegion")) {
                        regionCalls[0]++;
                        if (unsupported[0]) {
                            throw new UnsupportedOperationException("Test fallback");
                        }
                        com.jme3.texture.Image image = ((com.jme3.texture.Texture) args[0]).getImage();
                        int x0 = (int) args[1];
                        int y0 = (int) args[2];
                        int z0 = (int) args[3];
                        int w = (int) args[4];
                        int h = (int) args[5];
                        int d = (int) args[6];
                        java.nio.FloatBuffer packed = ((java.nio.ByteBuffer) args[7]).asFloatBuffer();
                        assertEquals(w * h * d, packed.remaining());
                        for (int z = z0; z < z0 + d; z++) {
                            for (int y = y0; y < y0 + h; y++) {
                                for (int x = x0; x < x0 + w; x++) {
                                    gpu.get(image)[x + 24 * (y + 24 * z)] = packed.get();
                                }
                            }
                        }
                    }
                    return null;
                });
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO, renderer);
            assertEquals(field.getTextureBytes(), field.getUploadedBytes());
            java.util.Random random = new java.util.Random(81);
            for (int step = 0; step < 20; step++) {
                box.setLocalTranslation(random.nextFloat() * 12 - 6, random.nextFloat() * 4 - 2, 0);
                box.setLocalScale(.3f + random.nextFloat(), .7f, -1);
                box.setLocalRotation(new Quaternion().fromAngles(.1f, random.nextFloat(), .2f));
                root.updateGeometricState();
                unsupported[0] = step == 10;
                field.update(root, Vector3f.ZERO, renderer);
                assertMatchesFresh(root, Vector3f.ZERO, field);
                assertGpuMatches(field, gpu);
            }
            assertTrue(regionCalls[0] > 0);
            field.update(root, Vector3f.ZERO, renderer);
            assertEquals(0, field.getUploadedBytes());
            field.getTexture(0).getImage().resetObject();
            field.update(root, Vector3f.ZERO, renderer);
            assertEquals(field.getTextureBytes() / 2, field.getUploadedBytes());
            assertGpuMatches(field, gpu);
        }
    }

    private void assertGpuMatches(GlobalDistanceField field,
            java.util.Map<com.jme3.texture.Image, float[]> gpu) {
        for (int level = 0; level < 2; level++) {
            float[] pixels = gpu.get(field.getTexture(level).getImage());
            for (int z = 0; z < 24; z++) {
                for (int y = 0; y < 24; y++) {
                    for (int x = 0; x < 24; x++) {
                        assertEquals(field.getDistance(level, x, y, z), pixels[x + 24 * (y + 24 * z)], 0);
                    }
                }
            }
        }
    }

    @Test
    public void staticUnionSurvivesDynamicOverlapPromotionRemovalAndScrolling() {
        Node root = new Node();
        Geometry source = bakedBox();
        Geometry mover = source.clone(false);
        root.attachChild(source);
        root.attachChild(mover);
        Geometry another = source.clone(false);
        another.setLocalTranslation(-3, 0, 0);
        root.attachChild(another);
        root.updateGeometricState();
        Vector3f camera = new Vector3f();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, camera);
            for (int step = 0; step < 16; step++) {
                mover.setLocalTranslation(step * .3f - 2, .2f, 0);
                if (step == 4) {
                    source.move(.2f, 0, .1f);
                }
                if (step == 6) {
                    another.removeFromParent();
                }
                if (step == 8) {
                    camera.set(.5f, 0, 0);
                }
                if (step == 10) {
                    root.attachChild(another);
                }
                if (step == 12) {
                    mover.removeFromParent();
                }
                root.updateGeometricState();
                field.update(root, camera);
                assertMatchesFresh(root, camera, field);
            }
        }
    }

    private Geometry bakedBox() {
        Geometry geometry = new Geometry("Baked", new Box(1, 1, 1));
        SdfGenerator.bake(geometry, new SdfSettings(13, 13, 13, .2f,
                SdfSettings.SignMode.EVEN_ODD), null).attachTo(geometry);
        return geometry;
    }

    @Test
    public void unchangedFramesDoNoWorkAndUnbakedGeometryIsIgnored() {
        Node root = new Node();
        root.attachChild(bakedBox());
        root.attachChild(new Geometry("Unbaked", new Box(3, 3, 3)));
        root.updateGeometricState();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO);
            assertEquals(1, field.getInstanceCount());
            assertEquals(24 * 24 * 24 * 2, field.getUpdatedVoxelCount());
            assertTrue(field.getDistance(0, 12, 12, 12) < 0);
            field.update(root, new Vector3f(.1f, .1f, .1f));
            assertEquals(0, field.getUpdatedVoxelCount());
        }
    }

    @Test
    public void scrollingAndChangedTransformsMatchFullRecomposition() {
        Node root = new Node();
        Geometry box = bakedBox();
        root.attachChild(box);
        root.updateGeometricState();
        try (GlobalDistanceField incremental = new GlobalDistanceField(24, 2, .5f)) {
            incremental.update(root, Vector3f.ZERO);
            Vector3f camera = new Vector3f(.5f, -1, 1.5f);
            incremental.update(root, camera);
            assertTrue(incremental.getUpdatedVoxelCount() < 24 * 24 * 24 * 2);
            assertMatchesFresh(root, camera, incremental);
            box.setLocalTranslation(3, 1, 0);
            box.setLocalScale(-2, .5f, 1.5f);
            box.setLocalRotation(new Quaternion().fromAngles(.2f, .8f, .3f));
            root.updateGeometricState();
            incremental.update(root, camera);
            assertMatchesFresh(root, camera, incremental);
            box.removeFromParent();
            root.updateGeometricState();
            incremental.update(root, camera);
            assertEquals(0, incremental.getInstanceCount());
            assertMatchesFresh(root, camera, incremental);
            assertEquals(3f, incremental.getDistance(0, 12, 12, 12), 0);
        }
    }

    @Test
    public void replacingSourceAndTeleportingDoesNotRetainOldValues() {
        Node root = new Node();
        Geometry box = bakedBox();
        root.attachChild(box);
        root.updateGeometricState();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO);
            Geometry other = new Geometry("Larger", new Box(2, 2, 2));
            SdfGenerator.bake(other, new SdfSettings(13, 13, 13, .2f,
                    SdfSettings.SignMode.UNSIGNED), null).attachTo(box);
            field.update(root, Vector3f.ZERO);
            assertMatchesFresh(root, Vector3f.ZERO, field);
            Vector3f camera = new Vector3f(100, -200, 300);
            field.update(root, camera);
            assertMatchesFresh(root, camera, field);
        }
    }

    @Test
    public void bakedAncestorOwnsSubtreeAndZeroScaleIsRejected() {
        Node root = new Node();
        Geometry box = bakedBox();
        root.attachChild(box);
        SdfVolume data = box.getUserData(SdfVolume.USER_DATA_KEY);
        data.attachTo(root);
        root.updateGeometricState();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO);
            assertEquals(1, field.getInstanceCount());
            root.setLocalScale(0);
            root.updateGeometricState();
            try {
                field.update(root, Vector3f.ZERO);
                fail("Singular transform accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid transforms must fail explicitly instead of producing NaNs.
            }
        }
    }

    private void assertMatchesFresh(Node root, Vector3f camera, GlobalDistanceField incremental) {
        try (GlobalDistanceField fresh = new GlobalDistanceField(24, 2, .5f)) {
            fresh.update(root, camera);
            for (int level = 0; level < 2; level++) {
                assertEquals(fresh.getMin(level), incremental.getMin(level));
                for (int z = 0; z < 24; z++) {
                    for (int y = 0; y < 24; y++) {
                        for (int x = 0; x < 24; x++) {
                            assertEquals("level=" + level + " xyz=" + x + "," + y + "," + z,
                                    fresh.getDistance(level, x, y, z), incremental.getDistance(level, x, y, z), 1e-5f);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void worldTransformAndRuntimeUserDataRemovalAreApplied() {
        Node root = new Node();
        Geometry box = bakedBox();
        box.setLocalTranslation(2, 1, -1);
        box.setLocalScale(-2, .5f, 1);
        box.setLocalRotation(new Quaternion().fromAngles(.2f, .8f, .3f));
        root.attachChild(box);
        root.updateGeometricState();
        try (GlobalDistanceField field = new GlobalDistanceField(24, 2, .5f)) {
            field.update(root, Vector3f.ZERO);
            // Grid minimum is (-6,-6,-6); transformed local origin is (2,1,-1).
            assertTrue(field.getDistance(0, 16, 14, 10) < 0);
            assertTrue(field.getDistance(0, 4, 4, 4) > 0);
            box.setUserData(SdfVolume.USER_DATA_KEY, null);
            field.update(root, Vector3f.ZERO);
            assertEquals(0, field.getInstanceCount());
            assertEquals(3, field.getDistance(0, 16, 14, 10), 0);
        }
    }
}

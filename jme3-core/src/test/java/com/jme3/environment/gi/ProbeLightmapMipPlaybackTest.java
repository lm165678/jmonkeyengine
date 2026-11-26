package com.jme3.environment.gi;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture.MinFilter;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProbeLightmapMipPlaybackTest {
    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void switchesResidentMipFilteringWithoutReplacingTheTexture() throws Exception {
        int baseBytes = 4 * 8 * 8;
        int firstMipBytes = 2 * 4 * 8;
        Image image = new Image(Image.Format.RGBA16F, 4, 8,
                BufferUtils.createByteBuffer(baseBytes + firstMipBytes),
                new int[]{baseBytes, firstMipBytes}, ColorSpace.Linear);
        Texture2D texture = new Texture2D(image);
        texture.setAnisotropicFilter(8);
        new BinaryExporter().save(new ProbeLightmapAsset(texture, true), files.newFile("mip.j3o"));
        DesktopAssetManager assets = manager();
        Geometry geometry = receiver("mip.j3o");
        ProbeLightmapControl control = geometry.getControl(ProbeLightmapControl.class);
        try {
            Texture2D resident = awaitTexture(assets, geometry, false);
            assertArrayEquals(new int[]{baseBytes, firstMipBytes}, resident.getImage().getMipMapSizes());
            assertEquals(MinFilter.BilinearNoMipMaps, resident.getMinFilter());
            assertEquals(1, resident.getAnisotropicFilter());

            control.updateResidency(assets, Vector3f.ZERO, true, true);
            assertSame(resident, geometry.getResidentProbeLightmap());
            assertEquals(MinFilter.Trilinear, resident.getMinFilter());
            assertFalse(resident.getImage().isGeneratedMipmapsRequired());

            control.updateResidency(assets, Vector3f.ZERO, true, false);
            assertSame(resident, geometry.getResidentProbeLightmap());
            assertEquals(MinFilter.BilinearNoMipMaps, resident.getMinFilter());
        } finally {
            control.clearResidency();
        }
    }

    @Test
    public void legacyAssetKeepsBaseFilteringWithoutGeneratingMips() throws Exception {
        Image image = new Image(Image.Format.RGBA16F, 4, 4, BufferUtils.createByteBuffer(4 * 4 * 8),
                ColorSpace.Linear);
        Texture2D texture = new Texture2D(image);
        texture.setMinFilter(MinFilter.BilinearNoMipMaps);
        new BinaryExporter().save(texture, files.newFile("legacy.j3o"));
        DesktopAssetManager assets = manager();
        Geometry geometry = receiver("legacy.j3o");
        ProbeLightmapControl control = geometry.getControl(ProbeLightmapControl.class);
        try {
            Texture2D resident = awaitTexture(assets, geometry, true);
            assertEquals(MinFilter.BilinearNoMipMaps, resident.getMinFilter());
            assertFalse(resident.getImage().hasMipmaps());
            assertFalse(resident.getImage().isGeneratedMipmapsRequired());
        } finally {
            control.clearResidency();
        }
    }

    @Test
    public void lightmapPlaybackDefaultsOnAndExplicitOffRoundTrips() throws Exception {
        Node scene = new Node("Scene");
        WorldSettings world = new WorldSettings();
        scene.addControl(world);
        File file = files.newFile("scene.j3o");
        assertTrue(world.isLightmapMipEnabled());
        assertTrue(world.isDirectionalLightmapEnabled());
        assertTrue(roundTrip(scene, file).isLightmapMipEnabled());
        assertTrue(roundTrip(scene, file).isDirectionalLightmapEnabled());
        world.setLightmapMipEnabled(false);
        world.setDirectionalLightmapEnabled(false);
        assertFalse(roundTrip(scene, file).isLightmapMipEnabled());
        assertFalse(roundTrip(scene, file).isDirectionalLightmapEnabled());
    }

    private DesktopAssetManager manager() {
        DesktopAssetManager assets = new DesktopAssetManager();
        assets.registerLocator(files.getRoot().getAbsolutePath(), FileLocator.class);
        return assets;
    }

    private static Geometry receiver(String asset) {
        Geometry geometry = new Geometry("Receiver", new Quad(1, 1));
        geometry.setMobility(Mobility.Static);
        geometry.updateGeometricState();
        geometry.addControl(new ProbeLightmapControl(asset, geometry.getWorldTransform(), 10f));
        return geometry;
    }

    private static Texture2D awaitTexture(DesktopAssetManager assets, Geometry geometry, boolean useMips)
            throws InterruptedException {
        ProbeLightmapControl control = geometry.getControl(ProbeLightmapControl.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            control.updateResidency(assets, Vector3f.ZERO, true, useMips);
            Texture2D texture = geometry.getResidentProbeLightmap();
            if (texture != null) {
                return texture;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError("Lightmap did not become resident");
    }

    private static WorldSettings roundTrip(Node scene, File target) throws Exception {
        new BinaryExporter().save(scene, target);
        return ((Node) new BinaryImporter().load(target)).getControl(WorldSettings.class);
    }
}

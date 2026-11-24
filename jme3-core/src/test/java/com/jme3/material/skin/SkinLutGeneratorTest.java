package com.jme3.material.skin;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkinLutGeneratorTest {
    @Test
    void flatLimitAndRgbResponse() {
        SkinLutGenerator.Result lut = SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 65, 33, 1f, 128);
        for (int x = 0; x < 65; x++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(Math.max(0f, x / 32f - 1f), lut.get(x, 0, c), 1e-6f);
                for (int y = 0; y < 33; y++) {
                    assertTrue(Float.isFinite(lut.get(x, y, c)));
                    assertTrue(lut.get(x, y, c) >= 0f && lut.get(x, y, c) <= 1f);
                }
            }
        }
        assertTrue(lut.get(32, 16, 0) > lut.get(32, 16, 1));
        assertTrue(lut.get(32, 16, 1) > lut.get(32, 16, 2));
        assertTrue(lut.get(32, 16, 2) > 0f);
    }

    @Test
    void normalizationAndPhysicalDistanceScaling() {
        float[] variance = {1f};
        float[][] weights = {{1f, 2f, 3f}};
        SkinLutGenerator.Profile unit = new SkinLutGenerator.Profile(variance, weights, new float[]{1f, 1f, 1f});
        variance[0] = 100f;
        weights[0][0] = 100f;
        assertEquals(1f, unit.variance(0));
        assertEquals(1f, unit.weight(0, 0));
        SkinLutGenerator.Profile half = new SkinLutGenerator.Profile(new float[]{1f},
                new float[][]{{1f, 2f, 3f}}, new float[]{.5f, .5f, .5f});
        SkinLutGenerator.Result a = SkinLutGenerator.generate(unit, 17, 17, .5f, 64);
        SkinLutGenerator.Result b = SkinLutGenerator.generate(half, 17, 17, 1f, 64);
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 17; x++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(a.get(x, y, 0), a.get(x, y, c), 1e-6f);
                    assertEquals(a.get(x, y, c), b.get(x, y, c), 1e-6f);
                }
            }
        }
    }

    @Test
    void quadratureConvergesWithoutFlatSurfaceAliasing() {
        SkinLutGenerator.Result a = SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 33, 17, 1f, 64);
        SkinLutGenerator.Result b = SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 33, 17, 1f, 256);
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 33; x++) {
                for (int c = 0; c < 3; c++) {
                    assertEquals(a.get(x, y, c), b.get(x, y, c), .002f);
                }
            }
        }
        assertEquals(1f, a.get(32, 1, 0), .001f);
        assertTrue(a.get(16, 1, 0) > 0f);
    }

    @Test
    void packagedMaterialAndEmbeddedImage() throws Exception {
        DesktopAssetManager assets = new DesktopAssetManager(true);
        Material packaged = assets.loadMaterial("Common/Materials/PreintegratedSkin.j3m");
        SkinLutGenerator.Result lut = SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 256, 128, 1f, 128);
        Texture2D texture = lut.createTexture();
        Texture loaded = packaged.getTextureParam("SkinLut").getTextureValue();
        assertEquals(texture.getImage(), loaded.getImage());
        assertEquals(ColorSpace.Linear, loaded.getImage().getColorSpace());
        assertEquals(Texture.MinFilter.BilinearNoMipMaps, loaded.getMinFilter());
        assertNull(packaged.getMaterialDef().getTechniqueDefs("GBuffer"));
        assertNotNull(packaged.getMaterialDef().getTechniqueDefs("SurfaceCapture"));
        lut.applyTo(packaged);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(packaged, bytes);
        BinaryImporter importer = new BinaryImporter();
        importer.setAssetManager(assets);
        Material copy = (Material) importer.load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(texture.getImage(), copy.getTextureParam("SkinLut").getTextureValue().getImage());
    }

    @Test
    void cancellationDoesNotPublishPartialData() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> SkinLutGenerator.generate(
                    SkinLutGenerator.referenceProfile(), 16, 16, 1f, 64));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidProfilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SkinLutGenerator.Profile(new float[]{0f},
                new float[][]{{1f, 1f, 1f}}, new float[]{1f, 1f, 1f}));
        assertThrows(IllegalArgumentException.class, () -> new SkinLutGenerator.Profile(new float[]{1f},
                new float[][]{{0f, 1f, 1f}}, new float[]{1f, 1f, 1f}));
        assertThrows(IllegalArgumentException.class, () -> SkinLutGenerator.generate(
                SkinLutGenerator.referenceProfile(), 1, 16, 1f, 128));
    }
}

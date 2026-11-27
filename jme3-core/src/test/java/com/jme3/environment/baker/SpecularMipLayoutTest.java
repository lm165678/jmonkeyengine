package com.jme3.environment.baker;

import com.jme3.export.Savable;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.light.LightProbe;
import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SpecularMipLayoutTest {
    private static final int CUBE_SIZE = 128;
    private static final int[] LEGACY_SIZES = {8192, 2048, 512, 128, 32, 8};
    private static final int[] ACTUAL_SIZES = {131072, 32768, 8192, 2048, 512, 128};

    @Test
    public void calculatesCappedChainFromTheOriginalDimensions() {
        var cube = new TextureCubeMap(CUBE_SIZE, CUBE_SIZE, Image.Format.RGBA16F);
        assertArrayEquals(ACTUAL_SIZES, SpecularMipLayout.sizes(cube.getImage(), 6));
    }

    @Test
    public void deserializesSkyWithRepairedCappedChain() throws IOException {
        var oldMap = cube(Image.Format.RGBA16F, LEGACY_SIZES, ACTUAL_SIZES);
        var sky = new SkyLight();
        sky.setPrefilteredMap(oldMap);
        sky.setNbMipMaps(LEGACY_SIZES.length);
        sky.setShCoeffs(coefficients());
        sky.setReady(true);

        SkyLight loaded = roundTrip(sky, SkyLight.class);

        assertTrue(loaded.isReady());
        assertEquals(ACTUAL_SIZES.length, loaded.getNbMipMaps());
        assertRepaired(oldMap, loaded.getPrefilteredEnvMap(), ACTUAL_SIZES);
    }

    @Test
    public void deserializesReflectionProbeWithRepairedTruncatedChain() throws IOException {
        int[] actual = Arrays.copyOf(ACTUAL_SIZES, 4);
        int[] legacy = Arrays.copyOf(LEGACY_SIZES, actual.length);
        var oldMap = cube(Image.Format.RGBA16F, legacy, actual);
        var probe = new LightProbe();
        probe.setPrefilteredMap(oldMap);
        probe.setNbMipMaps(legacy.length);
        probe.setShCoeffs(coefficients());
        probe.setReady(true);

        LightProbe loaded = roundTrip(probe, LightProbe.class);

        assertTrue(loaded.isReady());
        assertEquals(actual.length, loaded.getNbMipMaps());
        assertRepaired(oldMap, loaded.getPrefilteredEnvMap(), actual);
    }

    @Test
    public void leavesValidAndUnrecognizedTablesUnchanged() {
        var valid = cube(Image.Format.RGBA16F, ACTUAL_SIZES, ACTUAL_SIZES);
        assertUnchanged(valid);

        int[] unrecognized = LEGACY_SIZES.clone();
        unrecognized[1]++;
        assertUnchanged(cube(Image.Format.RGBA16F, unrecognized, ACTUAL_SIZES));
    }

    @Test
    public void leavesIncompleteAndCompressedPayloadsUnchanged() {
        var incomplete = cube(Image.Format.RGBA16F, LEGACY_SIZES, ACTUAL_SIZES);
        int shortSize = incomplete.getImage().getData(5).capacity() - 1;
        incomplete.getImage().setData(5, BufferUtils.createByteBuffer(shortSize));
        assertUnchanged(incomplete);

        // Deliberately resemble the old table, even though DXT5 stores blocks.
        int[] compressedLegacySizes = {1024, 256, 64, 16, 4, 1};
        int[] hypotheticalUncompressedSizes = {16384, 4096, 1024, 256, 64, 16};
        assertUnchanged(cube(Image.Format.DXT5, compressedLegacySizes, hypotheticalUncompressedSizes));
    }

    private static TextureCubeMap cube(Image.Format format, int[] table, int[] payloadSizes) {
        var faces = new ArrayList<ByteBuffer>();
        int faceSize = Arrays.stream(payloadSizes).sum();
        for (int face = 0; face < 6; face++) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(faceSize);
            for (int offset = 0; offset < faceSize; offset++) {
                pixels.put((byte) (offset + (offset >> 8) + face));
            }
            pixels.flip();
            faces.add(pixels);
        }
        Image image = new Image(format, CUBE_SIZE, CUBE_SIZE, 0, faces, table.clone(), ColorSpace.Linear);
        return new TextureCubeMap(image);
    }

    private static Vector3f[] coefficients() {
        Vector3f[] coefficients = new Vector3f[9];
        Arrays.setAll(coefficients, index -> new Vector3f(1f, 1f, 1f));
        return coefficients;
    }

    private static <T extends Savable> T roundTrip(T object, Class<T> type) throws IOException {
        var bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(object, bytes);
        return type.cast(new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray())));
    }

    private static void assertRepaired(TextureCubeMap originalMap, TextureCubeMap repairedMap, int[] actual) {
        var original = originalMap.getImage();
        var repaired = repairedMap.getImage();
        assertArrayEquals(actual, repaired.getMipMapSizes());
        assertEquals(6, repaired.getData().size());
        for (int face = 0; face < 6; face++) {
            assertEquals(original.getData(face).duplicate().clear(), repaired.getData(face).duplicate().clear());
        }
    }

    private static void assertUnchanged(TextureCubeMap map) {
        int[] original = map.getImage().getMipMapSizes();
        SpecularMipLayout.repairLegacy(map);
        assertSame(original, map.getImage().getMipMapSizes());
    }
}

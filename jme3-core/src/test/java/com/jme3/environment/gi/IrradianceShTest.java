package com.jme3.environment.gi;

import com.jme3.environment.util.CubeMapWrapper;
import com.jme3.environment.util.EnvMapUtils;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.texture.Image;
import com.jme3.texture.TextureCubeMap;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public class IrradianceShTest {
    @Test
    public void hdrCubemapNativeAndVolumeAgreeInAllDirections() {
        int size = 64;
        ArrayList<ByteBuffer> faces = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            faces.add(BufferUtils.createByteBuffer(size * size * 16));
        }
        TextureCubeMap cube = new TextureCubeMap(new Image(Image.Format.RGBA32F,
                size, size, 0, faces, ColorSpace.Linear));
        CubeMapWrapper pixels = new CubeMapWrapper(cube);
        Vector3f direction = new Vector3f();
        for (int face = 0; face < 6; face++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    EnvMapUtils.getVectorFromCubemapFaceTexCoord(x, y, size, face,
                            direction, EnvMapUtils.FixSeamsMethod.Wrap);
                    pixels.setPixel(x, y, face, new ColorRGBA(4 + 2 * direction.x,
                            3 + direction.y, 2 + direction.z, 1));
                }
            }
        }
        Vector3f[] nativeSh = EnvMapUtils.getSphericalHarmonicsCoefficents(cube);
        EnvMapUtils.prepareShCoefs(nativeSh);
        ColorRGBA sample = new ColorRGBA();
        float[] volume = GiProbeBaker.project(Vector3f.ZERO, 65536, (p, d, out) -> {
            pixels.getPixel(d, sample);
            out.set(sample.r, sample.g, sample.b);
        }, () -> false);
        float[] converted = IrradianceSh.fromLightProbe(nativeSh);
        for (int i = 0; i < 27; i++) {
            assertEquals("coefficient " + i, converted[i], volume[i], 0.08f);
        }
        for (int i = 0; i < 300; i++) {
            direction.set((float) Math.sin(i * 1.1), (float) Math.cos(i * 0.7),
                    (float) Math.sin(i * 2.3)).normalizeLocal();
            Vector3f expected = IrradianceSh.evaluate(converted, direction);
            Vector3f actual = IrradianceSh.evaluate(volume, direction);
            assertTrue("direction " + direction + " " + expected + " vs " + actual,
                    expected.distance(actual) < 0.1f);
        }
        Vector3f[] roundTrip = IrradianceSh.toLightProbe(converted);
        for (int i = 0; i < 9; i++) {
            assertTrue(nativeSh[i].distance(roundTrip[i]) < 0.00001f);
        }
        cube.getImage().dispose();
    }

    @Test
    public void constantHdrRadianceHasPiIrradiance() {
        float[] sh = GiProbeBaker.project(Vector3f.ZERO, 16384,
                (p, d, result) -> result.set(8, 4, 2), () -> false);
        Vector3f e = IrradianceSh.evaluate(sh, Vector3f.UNIT_Y);
        assertEquals(8 * Math.PI, e.x, 0.001);
        assertEquals(4 * Math.PI, e.y, 0.001);
        assertEquals(2 * Math.PI, e.z, 0.001);
    }
}

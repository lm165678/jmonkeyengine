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
    /** Analytic hemisphere integrals catch axis, sign and band-normalization errors. */
    @Test
    public void directionalAndQuadraticRadianceMatchAnalyticIntegrals() {
        float[] linear = GiProbeBaker.project(Vector3f.ZERO, 65536,
                (p, n, out) -> out.set(2 + n.x, 3 + n.y, 4 + n.z), () -> false);
        float[] quadratic = GiProbeBaker.project(Vector3f.ZERO, 65536,
                (p, n, out) -> out.set(1 + n.x * n.x, 1 + n.y * n.y, 1 + n.z * n.z),
                () -> false);
        for (int i = 0; i < 512; i++) {
            double z = 1 - 2 * (i + 0.5) / 512;
            double radius = Math.sqrt(1 - z * z);
            double angle = i * Math.PI * (3 - Math.sqrt(5));
            Vector3f n = new Vector3f((float) (radius * Math.cos(angle)),
                    (float) (radius * Math.sin(angle)), (float) z);
            Vector3f actualLinear = IrradianceSh.evaluate(linear, n);
            Vector3f actualQuadratic = IrradianceSh.evaluate(quadratic, n);
            for (int channel = 0; channel < 3; channel++) {
                double axis = n.get(channel);
                assertEquals(Math.PI * (channel + 2 + 2.0 / 3 * axis),
                        actualLinear.get(channel), 0.0001);
                assertEquals(Math.PI * (1.25 + 0.25 * axis * axis),
                        actualQuadratic.get(channel), 0.0001);
            }
        }
    }

}

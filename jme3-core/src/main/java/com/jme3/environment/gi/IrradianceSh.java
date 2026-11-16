package com.jme3.environment.gi;

import com.jme3.math.Vector3f;

/**
 * Conversion between cosine-convolved real SH9 irradiance (27 RGB floats) and
 * LightProbe's prepared polynomial coefficients representing irradiance / pi.
 * These methods consume prepared LightProbe coefficients, NOT raw radiance SH
 * returned by EnvMapUtils.getSphericalHarmonicsCoefficents(). No gamma conversion
 * or additional convolution is performed. Existing serialized formats stay valid.
 * All methods are CPU-only and allocate owned results; inputs are not modified.
 * <pre>{@code
 * // 第一步：读取原生探针已经 prepareShCoefs 处理的系数。
 * float[] irradiance = IrradianceSh.fromLightProbe(probe.getShCoeffs());
 * // 第二步：统一按辐照度 E 求值；Lambert 材质再乘反照率并除以 pi。
 * Vector3f e = IrradianceSh.evaluate(irradiance, Vector3f.UNIT_Y);
 * // 第三步：需要旧格式时转换回去；不改变原探针或 GPU 资源。
 * Vector3f[] prepared = IrradianceSh.toLightProbe(irradiance);
 * }</pre>
 */
public final class IrradianceSh {
    private static final double[] BASIS = {0.28209479177387814,
        0.4886025119029199, 0.4886025119029199, 0.4886025119029199,
        1.0925484305920792, 1.0925484305920792, 0.31539156525252005,
        1.0925484305920792, 0.5462742152960396};

    private IrradianceSh() {
    }

    /** Converts prepared native coefficients to positive-basis irradiance SH9. */
    public static float[] fromLightProbe(Vector3f[] prepared) {
        if (prepared == null || prepared.length != 9) {
            throw new IllegalArgumentException("Nine prepared coefficients required");
        }
        float[] result = new float[27];
        for (int i = 0; i < 9; i++) {
            if (!Vector3f.isValidVector(prepared[i])) {
                throw new IllegalArgumentException("Finite coefficients required");
            }
            for (int c = 0; c < 3; c++) {
                result[i * 3 + c] = (float) (prepared[i].get(c) * Math.PI / BASIS[i]);
            }
        }
        validate(result);
        return result;
    }

    /** Converts irradiance SH9 to native prepared irradiance/pi coefficients. */
    public static Vector3f[] toLightProbe(float[] irradiance) {
        validate(irradiance);
        Vector3f[] result = new Vector3f[9];
        for (int i = 0; i < 9; i++) {
            float scale = (float) (BASIS[i] / Math.PI);
            result[i] = new Vector3f(irradiance[i * 3] * scale,
                    irradiance[i * 3 + 1] * scale, irradiance[i * 3 + 2] * scale);
        }
        return result;
    }

    /** Evaluates irradiance; normal is normalized internally. Clamps after all bands. */
    public static Vector3f evaluate(float[] irradiance, Vector3f normal) {
        validate(irradiance);
        if (!Vector3f.isValidVector(normal) || normal.lengthSquared() == 0) {
            throw new IllegalArgumentException("Finite nonzero normal required");
        }
        double[] basis = new double[9];
        GiProbeBaker.basis(normal.normalize(), basis);
        Vector3f result = new Vector3f();
        for (int c = 0; c < 3; c++) {
            double value = 0;
            for (int i = 0; i < 9; i++) {
                value += irradiance[i * 3 + c] * basis[i];
            }
            result.set(c, (float) Math.max(0, value));
        }
        return result;
    }

    private static void validate(float[] coefficients) {
        if (coefficients == null || coefficients.length != 27) {
            throw new IllegalArgumentException("27 irradiance coefficients required");
        }
        for (float value : coefficients) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Finite coefficients required");
            }
        }
    }
}

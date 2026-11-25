package com.jme3.material.skin;

import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Generates a linear pre-integrated diffuse response for skin. The normalized
 * ring integral follows Penner's curvature approximation. Gaussian distances
 * are in millimeters; pigmentation belongs in BaseColor/BaseColorMap.
 * No exposure, tone mapping or gamma encoding is applied to the LUT.
 *
 * <p>X represents N dot L in [-1,1]. Y represents sqrt(curvature / maximum
 * curvature) in [0,1], with domain endpoints at texel centers. The bottom row
 * is exactly Lambertian. Clamp/bilinear sampling without mipmaps is required.
 * LUTs generated with another coordinate convention cannot be substituted.
 *
 * <pre>{@code
 * // 第一步：生成 LUT，可在工作线程执行；不需要 OpenGL、JNI 编码器或子进程。
 * SkinLutGenerator.Result lut = SkinLutGenerator.generate(
 *         SkinLutGenerator.referenceProfile(), 256, 128, 1f, 128);
 * // 第二步：在初始化或渲染线程绑定材质，世界坐标按米换算为毫米。
 * Material skin = new Material(assetManager, SkinLutGenerator.MATERIAL);
 * lut.applyTo(skin);
 * skin.setFloat("SkinWorldToMillimeters", 1000f);
 * geometry.setMaterial(skin);
 * // 第三步：可选提供线性曲率图和平滑法线；否则自动估算/采样。
 * // skin.setTexture("SkinCurvatureMap", curvatureMap);
 * // skin.setTexture("SkinDiffuseNormalMap", blurredNormalMap);
 * // skin.setFloat("SkinStrength", 0f); // 恢复相同参数下的普通 PBR 漫反射。
 * }</pre>
 *
 * <p>For a ready-made material, load Common/Materials/PreintegratedSkin.j3m.
 * The default is nonmetallic, roughness .45, strength 1, and meter world units.
 * Scattering affects direct diffuse lighting; specular and indirect light use
 * the existing PBR utilities. Deferred viewports use forward fallback for skin.
 * Optional SkinTransmission adds a separate normal-thickness approximation; it is
 * disabled by default. See TestSkinTransmission and SkinSurfaceBaker. A
 * shadow-penumbra scattering LUT and full volumetric SSS are not provided.
 * GLSL330/ES300 and filterable half-float textures are required.
 *
 * <p>Generation is CPU-only and interruptible. Results and profiles are immutable;
 * create/bind textures on the render thread when materials are already in use.
 * Binary J3O may embed the image. Text J3M must reference an exported image asset.
 * The SDK Tools / Textures / Pre-integrated Skin Tools action exports both assets
 * and provides normal prefiltering plus offline thickness/curvature baking.
 * Its exporters can update an existing selected skin material without replacing
 * the LUT or unrelated artist parameters.
 * See TestPreintegratedSkin for the runnable example and verification command.
 */
public final class SkinLutGenerator {
    public static final String MATERIAL = "Common/MatDefs/Light/PreintegratedSkin.j3md";

    private SkinLutGenerator() {
    }

    /**
     * Returns the six-Gaussian reference fit described in GPU Gems 3 chapter 14
     * (d'Eon et al., 2007). This is not a measured population/skin-tone preset.
     *
     * @return an immutable profile with unit RGB distance scales
     */
    public static Profile referenceProfile() {
        return new Profile(new float[]{.0064f, .0484f, .187f, .567f, 1.99f, 7.41f},
                new float[][]{{.233f, .455f, .649f}, {.100f, .336f, .344f},
                    {.118f, .198f, 0f}, {.113f, .007f, .007f},
                    {.358f, .004f, 0f}, {.078f, 0f, 0f}}, new float[]{1f, 1f, 1f});
    }

    /**
     * Reference and artistic starting points, not demographic measurements.
     * GPU Gems 3 section14.4.3 describes its reference fit as a starting point
     * for Caucasian skin. The other distance multipliers below are authoring
     * choices; ethnicity does not uniquely determine absorption or scattering.
     * Albedo, blood/pigment variation and diffusion shape remain separate inputs.
     */
    public enum Preset {
        REFERENCE("Literature reference / Caucasian starting point", 1f, 1f, 1f),
        LIGHT_SOFT("Light skin / soft diffusion (artist)", 1.2f, 1.05f, .95f),
        MEDIUM_WARM("Medium warm skin (artist)", .85f, .75f, .65f),
        DARK_COMPACT("Dark skin / compact diffusion (artist)", .55f, .45f, .35f);

        private final String title;
        private final float[] scales;

        Preset(String title, float red, float green, float blue) {
            this.title = title;
            scales = new float[]{red, green, blue};
        }

        /** @return an immutable copy suitable for both LUT and normal filtering */
        public Profile createProfile() {
            Profile reference = referenceProfile();
            return new Profile(reference.variances, reference.weights, scales);
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Immutable Gaussian fit. Each channel is normalized during integration. */
    public static final class Profile {
        private final float[] variances;
        private final float[][] weights;
        private final float[] distances;

        /**
         * Copies a fit. Weights specify diffusion shape; absolute reflectance is
         * supplied separately by the material's albedo.
         *
         * @param variances positive finite variances in mm squared
         * @param weights nonnegative finite RGB weights, one triple per variance
         * @param distances three positive finite RGB distance multipliers
         */
        public Profile(float[] variances, float[][] weights, float[] distances) {
            Objects.requireNonNull(variances, "variances");
            Objects.requireNonNull(weights, "weights");
            Objects.requireNonNull(distances, "distances");
            if (variances.length == 0 || weights.length != variances.length || distances.length != 3) {
                throw new IllegalArgumentException("Each Gaussian needs a variance and RGB weights");
            }
            this.variances = variances.clone();
            this.distances = distances.clone();
            this.weights = new float[weights.length][3];
            double[] mass = new double[3];
            for (float distance : distances) {
                requirePositive(distance);
            }
            for (int i = 0; i < variances.length; i++) {
                requirePositive(variances[i]);
                if (weights[i] == null || weights[i].length != 3) {
                    throw new IllegalArgumentException("Expected RGB Gaussian weights");
                }
                for (int c = 0; c < 3; c++) {
                    float weight = weights[i][c];
                    if (!Float.isFinite(weight) || weight < 0f) {
                        throw new IllegalArgumentException("Weights must be finite and nonnegative");
                    }
                    this.weights[i][c] = weight;
                    mass[c] += weight;
                }
            }
            for (double value : mass) {
                if (value == 0.0) {
                    throw new IllegalArgumentException("Each channel needs positive diffusion mass");
                }
            }
        }

        /** @return number of Gaussian lobes */
        public int size() {
            return variances.length;
        }

        /**
         * @param lobe Gaussian index
         * @return variance in mm squared
         */
        public float variance(int lobe) {
            return variances[lobe];
        }

        /**
         * @param lobe Gaussian index
         * @param channel RGB index
         * @return lobe weight
         */
        public float weight(int lobe, int channel) {
            return weights[lobe][channel];
        }

        /**
         * @param channel RGB index
         * @return absolute distance multiplier
         */
        public float distance(int channel) {
            return distances[channel];
        }
    }

    /** Immutable linear response data; texture creation does not allocate GL objects. */
    public static final class Result {
        private final int width;
        private final int height;
        private final float maximumCurvature;
        private final float[] pixels;

        private Result(int width, int height, float maximumCurvature, float[] pixels) {
            this.width = width;
            this.height = height;
            this.maximumCurvature = maximumCurvature;
            this.pixels = pixels;
        }

        /** @return LUT width */
        public int getWidth() {
            return width;
        }

        /** @return LUT height */
        public int getHeight() {
            return height;
        }

        /** @return top-row curvature in inverse mm */
        public float getMaximumCurvature() {
            return maximumCurvature;
        }

        /**
         * @param x horizontal texel
         * @param y row from bottom
         * @param channel RGB index
         * @return linear response
         */
        public float get(int x, int y, int channel) {
            if (x < 0 || x >= width || y < 0 || y >= height || channel < 0 || channel > 2) {
                throw new IndexOutOfBoundsException();
            }
            return pixels[(y * width + x) * 3 + channel];
        }

        /** @return a new linear RGB16F texture, bilinear/clamped without mipmaps */
        public Texture2D createTexture() {
            ByteBuffer data = BufferUtils.createByteBuffer(pixels.length * 2);
            for (float pixel : pixels) {
                data.putShort(FastMath.convertFloatToHalf(pixel));
            }
            data.flip();
            Texture2D texture = new Texture2D(new Image(Image.Format.RGB16F, width, height, data, ColorSpace.Linear));
            texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            texture.setMagFilter(Texture.MagFilter.Bilinear);
            texture.setWrap(Texture.WrapMode.EdgeClamp);
            return texture;
        }

        /**
         * Binds a fresh texture and the matching coordinate metadata together.
         * Call on the render thread for a material already attached to a scene.
         *
         * @param material a material using {@link SkinLutGenerator#MATERIAL}
         */
        public void applyTo(Material material) {
            material.setTexture("SkinLut", createTexture());
            material.setVector2("SkinLutSize", new Vector2f(width, height));
            material.setFloat("SkinMaxCurvature", maximumCurvature);
        }
    }

    /**
     * Convolves cosine lighting over a ring using Gauss-Legendre quadrature.
     * Limits adapt to each lobe's six-sigma support, avoiding narrow-kernel
     * undersampling on broad surfaces. Interrupted generation throws
     * CancellationException and publishes no partial result.
     *
     * @param profile Gaussian fit in millimeters
     * @param width width, 2 through 2048
     * @param height height, 2 through 2048
     * @param maximumCurvature positive finite top-row curvature in inverse mm
     * @param samples quadrature points per lobe, 16 through 512
     * @return immutable linear RGB data
     */
    public static Result generate(Profile profile, int width, int height, float maximumCurvature, int samples) {
        Objects.requireNonNull(profile, "profile");
        requirePositive(maximumCurvature);
        if (width < 2 || width > 2048 || height < 2 || height > 2048 || samples < 16 || samples > 512) {
            throw new IllegalArgumentException("Unsupported LUT dimensions or quadrature size");
        }
        double[][] quadrature = quadrature(samples);
        float[] pixels = new float[width * height * 3];
        double[] cosTheta = new double[width];
        double[] sinTheta = new double[width];
        for (int x = 0; x < width; x++) {
            cosTheta[x] = 2.0 * x / (width - 1) - 1.0;
            sinTheta[x] = Math.sqrt(Math.max(0.0, 1.0 - cosTheta[x] * cosTheta[x]));
        }
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Skin LUT generation cancelled");
            }
            double fraction = (double) y / (height - 1);
            double curvature = maximumCurvature * fraction * fraction;
            for (int channel = 0; channel < 3; channel++) {
                double[] row = new double[width];
                double total = 0.0;
                if (y != 0) {
                    for (int lobe = 0; lobe < profile.size(); lobe++) {
                        double sigma = Math.sqrt(profile.variance(lobe)) * profile.distance(channel);
                        double limit = 2.0 * Math.asin(Math.min(1.0, 3.0 * sigma * curvature));
                        for (int sample = 0; sample < samples; sample++) {
                            double angle = quadrature[0][sample] * limit;
                            double radius = 2.0 * Math.sin(angle * .5) / (curvature * sigma);
                            double mass = profile.weight(lobe, channel) / sigma * Math.exp(-.5 * radius * radius)
                                    * quadrature[1][sample] * limit;
                            total += mass;
                            double cosine = Math.cos(angle);
                            double sine = Math.sin(angle);
                            for (int x = 0; x < width; x++) {
                                double a = cosTheta[x] * cosine;
                                double b = sinTheta[x] * sine;
                                row[x] += .5 * mass * (Math.max(0.0, a + b) + Math.max(0.0, a - b));
                            }
                        }
                    }
                }
                for (int x = 0; x < width; x++) {
                    double value = y == 0 ? Math.max(0.0, cosTheta[x]) : row[x] / total;
                    pixels[(y * width + x) * 3 + channel] = (float) Math.max(0.0, Math.min(1.0, value));
                }
            }
        }
        return new Result(width, height, maximumCurvature, pixels);
    }

    private static double[][] quadrature(int count) {
        double[][] result = new double[2][count];
        for (int i = 0; i < (count + 1) / 2; i++) {
            double root = Math.cos(Math.PI * (i + .75) / (count + .5));
            double derivative = 0.0;
            for (int iteration = 0; iteration < 32; iteration++) {
                double previous = 0.0;
                double polynomial = 1.0;
                for (int order = 1; order <= count; order++) {
                    double next = ((2.0 * order - 1.0) * root * polynomial - (order - 1.0) * previous) / order;
                    previous = polynomial;
                    polynomial = next;
                }
                derivative = count * (root * polynomial - previous) / (root * root - 1.0);
                double delta = polynomial / derivative;
                root -= delta;
                if (Math.abs(delta) < 1e-14) {
                    break;
                }
            }
            double weight = 1.0 / ((1.0 - root * root) * derivative * derivative);
            result[0][i] = (1.0 - root) * .5;
            result[0][count - 1 - i] = (1.0 + root) * .5;
            result[1][i] = weight;
            result[1][count - 1 - i] = weight;
        }
        return result;
    }

    private static void requirePositive(float value) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException("Expected a finite positive value");
        }
    }
}

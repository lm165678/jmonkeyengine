package com.jme3.material.skin;

import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * CPU prefilter for the four-normal form of Penner's skin shading: one detailed
 * specular normal and three profile-filtered diffuse normals. Gaussian lobes
 * convolve signed vector components; normalization occurs after RGB profile
 * accumulation. Encoded normal values are data and must not be gamma-decoded.
 *
 * <p>The input is interleaved encoded XYZ in [0,1]. OpenGL/DirectX Y conventions
 * are preserved. Texels/mm refers to this input image's resolution, assuming
 * uniform UV density. Resizing requires adjusting this scale. This is texture
 * filtering, not curvature generation or geometry-aware UV seam repair; charts
 * need suitable padding. Clamp and wrap describe only the image boundary.
 *
 * <pre>{@code
 * // 第一步：从法线图读取 RGB 数据，不作 sRGB 转线性颜色变换。
 * // 第二步：按纹理的实际密度换算毫米，和 LUT 共用扩散剖面。
 * SkinNormalFilter.Result maps = SkinNormalFilter.generate(
 *         encodedRgb, width, height, SkinLutGenerator.referenceProfile(),
 *         4f, SkinNormalFilter.Boundary.CLAMP, SkinNormalFilter.Quality.EXACT);
 * // 第三步：将 R/G/B 结果编码为线性法线纹理，分别绑定材质参数。
 * // skin.setTexture("SkinRedNormalMap", redMap);
 * // skin.setTexture("SkinGreenNormalMap", greenMap);
 * // skin.setTexture("SkinBlueNormalMap", blueMap);
 * // 双法线近似可只把红通道结果绑定 SkinDiffuseNormalMap。
 * // 清除这三个参数和 SkinDiffuseNormalMap 即恢复单图 mip 采样。
 * }</pre>
 *
 * <p>Generation is interruptible and has no GL/native-codec dependency. EXACT
 * uses separable Gaussian kernels truncated at three sigma. FAST retains exact
 * small kernels and approximates broader Gaussians with three box passes.
 * The latter is an authoring-speed approximation, not the exact convolution.
 */
public final class SkinNormalFilter {
    public enum Boundary {
        CLAMP,
        WRAP
    }

    public enum Quality {
        EXACT,
        FAST
    }

    private SkinNormalFilter() {
    }

    /** Immutable signed, unit-length normals: channel -1 is the detailed input. */
    public static final class Result {
        private final int width;
        private final int height;
        private final float[][] normals;

        private Result(int width, int height, float[][] normals) {
            this.width = width;
            this.height = height;
            this.normals = normals;
        }

        /** @return width in texels */
        public int getWidth() {
            return width;
        }

        /** @return height in texels */
        public int getHeight() {
            return height;
        }

        /**
         * @param channel -1 for input, 0/1/2 for R/G/B diffuse normals
         * @param x texel column
         * @param y texel row in input order
         * @param component X/Y/Z index
         * @return signed normalized component
         */
        public float get(int channel, int x, int y, int component) {
            if (channel < -1 || channel > 2 || x < 0 || x >= width || y < 0 || y >= height
                    || component < 0 || component > 2) {
                throw new IndexOutOfBoundsException();
            }
            return normals[channel + 1][(y * width + x) * 3 + component];
        }
    }

    /**
     * @param encodedRgb interleaved, finite XYZ values in [0,1]
     * @param width input width, 1 through 2048
     * @param height input height, 1 through 2048
     * @param profile same diffusion fit used to generate the lighting LUT
     * @param texelsPerMillimeter positive, uniform density of this input image
     * @param boundary image-edge policy
     * @param quality exact or fast Gaussian filtering
     * @return detailed plus three diffuse normal fields
     */
    public static Result generate(float[] encodedRgb, int width, int height,
            SkinLutGenerator.Profile profile, float texelsPerMillimeter,
            Boundary boundary, Quality quality) {
        Objects.requireNonNull(encodedRgb, "encodedRgb");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(quality, "quality");
        if (width < 1 || width > 2048 || height < 1 || height > 2048
                || encodedRgb.length != width * height * 3
                || !Float.isFinite(texelsPerMillimeter) || texelsPerMillimeter <= 0f) {
            throw new IllegalArgumentException("Invalid normal image size or texel density");
        }
        int length = encodedRgb.length;
        float[][] normals = new float[4][length];
        for (int i = 0; i < length; i++) {
            float value = encodedRgb[i];
            if (!Float.isFinite(value) || value < 0f || value > 1f) {
                throw new IllegalArgumentException("Normal data must be finite encoded XYZ in [0,1]");
            }
            normals[0][i] = value * 2f - 1f;
        }
        normalize(normals[0]);
        float[] temporary = new float[length];
        float[] blurred = new float[length];
        for (int channel = 0; channel < 3; channel++) {
            for (int lobe = 0; lobe < profile.size(); lobe++) {
                interrupted();
                float weight = profile.weight(lobe, channel);
                if (weight == 0f) {
                    continue;
                }
                double sigma = Math.sqrt(profile.variance(lobe)) * profile.distance(channel) * texelsPerMillimeter;
                if (!Double.isFinite(sigma) || sigma > 256.0) {
                    throw new IllegalArgumentException("Filter radius is too large; reduce the density or diffusion distance");
                }
                if (quality == Quality.FAST && sigma > 4.0) {
                    fastGaussian(normals[0], temporary, blurred, width, height, sigma, boundary);
                } else {
                    float[] kernel = gaussian(sigma);
                    convolve(normals[0], temporary, width, height, true, kernel, boundary);
                    convolve(temporary, blurred, width, height, false, kernel, boundary);
                }
                for (int i = 0; i < length; i++) {
                    normals[channel + 1][i] += blurred[i] * weight;
                }
            }
            normalize(normals[channel + 1]);
        }
        return new Result(width, height, normals);
    }

    private static float[] gaussian(double sigma) {
        if (sigma < 1e-4) {
            return new float[]{1f};
        }
        int radius = Math.max(1, (int) Math.ceil(3.0 * sigma));
        float[] kernel = new float[2 * radius + 1];
        double mass = 0.0;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-.5 * i * i / (sigma * sigma));
            mass += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= mass;
        }
        return kernel;
    }

    private static void convolve(float[] input, float[] output, int width, int height,
            boolean horizontal, float[] kernel, Boundary boundary) {
        int radius = kernel.length / 2;
        for (int y = 0; y < height; y++) {
            interrupted();
            for (int x = 0; x < width; x++) {
                float nx = 0f;
                float ny = 0f;
                float nz = 0f;
                for (int k = -radius; k <= radius; k++) {
                    int sx = horizontal ? edge(x + k, width, boundary) : x;
                    int sy = horizontal ? y : edge(y + k, height, boundary);
                    int source = (sy * width + sx) * 3;
                    float weight = kernel[k + radius];
                    nx += input[source] * weight;
                    ny += input[source + 1] * weight;
                    nz += input[source + 2] * weight;
                }
                int target = (y * width + x) * 3;
                output[target] = nx;
                output[target + 1] = ny;
                output[target + 2] = nz;
            }
        }
    }

    private static void fastGaussian(float[] input, float[] temporary, float[] output,
            int width, int height, double sigma, Boundary boundary) {
        double ideal = Math.sqrt(4.0 * sigma * sigma + 1.0);
        int lower = (int) Math.floor(ideal);
        if (lower % 2 == 0) {
            lower--;
        }
        int countLower = (int) Math.round((12.0 * sigma * sigma - 3.0 * lower * lower - 12.0 * lower - 9.0)
                / (-4.0 * lower - 4.0));
        float[] source = input;
        for (int i = 0; i < 3; i++) {
            int radius = ((i < countLower ? lower : lower + 2) - 1) / 2;
            box(source, temporary, width, height, true, radius, boundary);
            box(temporary, output, width, height, false, radius, boundary);
            source = output;
        }
    }

    private static void box(float[] input, float[] output, int width, int height,
            boolean horizontal, int radius, Boundary boundary) {
        int lines = horizontal ? height : width;
        int extent = horizontal ? width : height;
        float inverse = 1f / (2 * radius + 1);
        for (int line = 0; line < lines; line++) {
            interrupted();
            double[] sums = new double[3];
            for (int k = -radius; k <= radius; k++) {
                int position = edge(k, extent, boundary);
                int index = (horizontal ? line * width + position : position * width + line) * 3;
                for (int c = 0; c < 3; c++) {
                    sums[c] += input[index + c];
                }
            }
            for (int pixel = 0; pixel < extent; pixel++) {
                int index = (horizontal ? line * width + pixel : pixel * width + line) * 3;
                int outgoing = edge(pixel - radius, extent, boundary);
                int incoming = edge(pixel + radius + 1, extent, boundary);
                int left = (horizontal ? line * width + outgoing : outgoing * width + line) * 3;
                int right = (horizontal ? line * width + incoming : incoming * width + line) * 3;
                for (int c = 0; c < 3; c++) {
                    output[index + c] = (float) sums[c] * inverse;
                    sums[c] += input[right + c] - input[left + c];
                }
            }
        }
    }

    private static int edge(int position, int extent, Boundary boundary) {
        return boundary == Boundary.WRAP ? Math.floorMod(position, extent) : Math.max(0, Math.min(extent - 1, position));
    }

    private static void normalize(float[] normals) {
        for (int i = 0; i < normals.length; i += 3) {
            double x = normals[i];
            double y = normals[i + 1];
            double z = normals[i + 2];
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1e-12) {
                normals[i] = 0f;
                normals[i + 1] = 0f;
                normals[i + 2] = 1f;
            } else {
                normals[i] /= length;
                normals[i + 1] /= length;
                normals[i + 2] /= length;
            }
        }
    }

    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Skin normal filtering cancelled");
        }
    }
}

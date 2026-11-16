package com.jme3.environment.gi;

import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Spatial;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Frame-budgeted render-thread scene baker for static diffuse GI. Captures linear
 * HDR color and hardware depth through six 90-degree cameras per probe. The
 * material's actual alpha discard, skinning, instancing and emission participate.
 * Existing GI bindings are disabled only in the private capture, preventing
 * accidental feedback. This is a single-bounce capture; rebaking does not iterate
 * indirect bounces. Dynamic geometry is captured at its current pose.
 *
 * <pre>{@code
 * // 第一步：在渲染线程、主视口绘制之前更新场景变换。
 * rootNode.updateGeometricState();
 * // 第二步：设置烘焙质量及最远遮挡距离；距离矩必须覆盖有效场景范围。
 * GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
 * quality.captureSize = 64;
 * quality.far = 200;
 * // 第三步：separateSky 默认开启，分开保存反弹光与天空可见性；烘焙；默认逐灯阴影，可在 configureCapture 中覆盖视口阴影配置。
 * GiProbeData data = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
 *         capture -> { }, () -> false, completed -> { });
 * // 第四步：绑定并保留恢复句柄；保存 volume 可连同烘焙资源一起序列化。
 * GiProbeData.Binding binding = data.bind(material, volume.getWorldTranslation(), 0.05f);
 * // 第五步：卸载时先 binding.close()，所有使用者释放后再 data.dispose()。
 * }</pre>
 * Requires FloatTexture and FloatColorBufferRGBA and three free texture units for
 * single-volume runtime sampling (four for a GiProbeSet). Unsupported hardware fails before changing state; no render
 * path is disabled. Capture always uses an independent Forward viewport. The
 * main viewport can continue using any pipeline, including FG and GpuScene/SDF.
 * Shadow capture is enabled by default: directional/point/spot lights use the
 * capture viewport's ShadowManager, respecting Light.castShadow and the scene's
 * Cast/Receive ShadowMode. Only each light's direct contribution is shadowed;
 * ambient light, emission and other lights are not multiplied by its visibility.
 * The light/map/byte budgets fail explicitly instead of dropping shadows. A custom
 * renderer supplied by configureCapture retains ownership of its light and
 * suppresses automatic handling; it retains legacy post-composition semantics.
 * For custom FPP shadow filters set captureShadows=false and supply NEW processors;
 * never share live processors. Geometry with ShadowMode.Off remains unshadowed.
 * Do not add tone mapping or display transforms. Capture uses raster shadow maps,
 * not a path-traced solver. shadowByteBudget covers depth maps and their atlas.
 * New bakes separate directly visible sky from surface radiance. Runtime adds
 * SkyLight diffuse multiplied by interpolated cosine-weighted sky visibility to
 * surface radiance SH. This is a low-frequency scalar visibility approximation:
 * strongly colored directional skies can require higher-fidelity transport.
 * Sky illumination still participates when shading captured surfaces. Set
 * Settings.separateSky=false only for legacy total-irradiance capture. Existing
 * total assets retain compatibility and should be rebaked before additive use.
 * Visibility defaults to 16 texels per axis. New bakes preserve independent
 * distance standard deviation for stable half-precision visibility. Existing
 * assets remain readable but require rebaking to benefit from this encoding.
 * <pre>{@code
 * // 第一步：默认 16；复杂遮挡可提升到 32，开销随分辨率平方增长。
 * GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
 * quality.visibilityResolution = 32;
 * // 第二步：保留半精度压缩；新版会额外保留稳定的距离标准差。
 * quality.compactStorage = true;
 * // 第三步：调用 start/bake 重新烘焙旧数据，再保存场景及外部 GI 文件。
 * }</pre>
 * Background radiance defaults to black. No ambient/environment light is
 * removed from the scene, so callers should avoid double-counting baked ambient.
 * Cancellation/failure preserves the previous volume data and restores forced
 * parameters, camera and framebuffer. Captured processors/resources are released.
 */
public final class GiSceneBaker {
    private GiSceneBaker() {
    }

    /** Explicit quality/budget settings; use smaller volumes instead of a world-sized atlas. */
    public static final class Settings {
        public int captureSize = 64;
        public int radianceSamples = 1024;
        /** Octahedral distance resolution, default 16 (four times the storage of
         * 8). Use 32 for difficult thin/concave surfaces; requires rebaking and
         * more memory, and does not replace adequate spatial probe coverage.
         */
        public int visibilityResolution = 16;
        public int visibilitySamples = 64;
        public int maxProbes = 4096;
        /** Conservative CPU working-set budget, including capture and output copies. */
        public long maxWorkingBytes = 128L * 1024 * 1024;
        public float near = 0.05f;
        public float far = 200f;
        /** Cone radius in radians used to prefilter directional distance moments. */
        public float visibilityCone = 0.15f;
        public ColorRGBA background = ColorRGBA.Black.clone();
        /** Prefer half-float storage; surface layouts always use full precision
         * for position offsets and exact spatial-bin indices. */
        public boolean compactStorage = true;
        /** Separate directly visible sky from bounced radiance (default true).
         * Sky remains in capture to illuminate surfaces. Visibility SH modulates
         * runtime SkyLight; this low-frequency scalar occlusion approximates
         * directional colored-sky transport, not exact Lumen ray tracing. */
        public boolean separateSky = true;
        public boolean classifyProbes = true;
        /** Minimum world-space clearance, default 0.05. Classification uses at
         * least 1.25 times near, preventing surfaces clipped by capture cameras.
         * Relocation remains bounded to 45% of a grid step; impossible placements
         * are invalid, never silently moved into another cell. Requires rebaking.
         */
        public float probeClearance = 0.05f;
        /** Additional clearance as a fraction of the smallest grid spacing,
         * default 0.02. Combined with probeClearance and the capture near plane
         * using max; zero restores absolute-only clearance. Requires rebaking.
         * <pre>{@code
         * // 第一步：随局部网格尺度保留安全间距，避免探针紧贴表面。
         * quality.relativeProbeClearance = 0.02f;
         * // 第二步：微小场景可设为 0，仅使用绝对间距及近裁剪面限制。
         * }</pre>
         */
        public float relativeProbeClearance = 0.02f;
        /** Content revision including geometry, lighting and validator; required for checkpoints. */
        public String sceneRevision = "";
        public ProbeValidator validator;
        /** Capture direct-light shadowing by default; respects Spatial ShadowMode. */
        public boolean captureShadows = true;
        public int shadowMapSize = 512;
        public int maxShadowLights = 16;
        public long shadowByteBudget = 64L * 1024 * 1024;

        Settings copy() {
            Settings copy = new Settings();
            copy.captureSize = captureSize;
            copy.radianceSamples = radianceSamples;
            copy.visibilityResolution = visibilityResolution;
            copy.visibilitySamples = visibilitySamples;
            copy.maxProbes = maxProbes;
            copy.maxWorkingBytes = maxWorkingBytes;
            copy.near = near;
            copy.far = far;
            copy.visibilityCone = visibilityCone;
            copy.background = background.clone();
            copy.compactStorage = compactStorage;
            copy.separateSky = separateSky;
            copy.classifyProbes = classifyProbes;
            copy.probeClearance = probeClearance;
            copy.relativeProbeClearance = relativeProbeClearance;
            copy.sceneRevision = sceneRevision;
            copy.validator = validator;
            copy.captureShadows = captureShadows;
            copy.shadowMapSize = shadowMapSize;
            copy.maxShadowLights = maxShadowLights;
            copy.shadowByteBudget = shadowByteBudget;
            return copy;
        }

        private void validate() {
            if (captureSize < 4 || captureSize > 2048 || radianceSamples < 32
                    || visibilityResolution < 2 || visibilityResolution > 64
                    || visibilitySamples < 1 || visibilitySamples > 4096
                    || maxProbes < 8 || maxProbes > 65536 || maxWorkingBytes < 1
                    || !Float.isFinite(near) || !Float.isFinite(far) || near <= 0 || far <= near
                    || far > 1e10f || !Float.isFinite(visibilityCone)
                    || visibilityCone <= 0 || visibilityCone > 1 || radianceSamples > 65536
                    || !Float.isFinite(probeClearance) || probeClearance <= 0
                    || !Float.isFinite(relativeProbeClearance) || relativeProbeClearance < 0 || relativeProbeClearance > 0.25f
                    || shadowMapSize < 64 || shadowMapSize > 4096 || maxShadowLights < 0 || shadowByteBudget < 0) {
                throw new IllegalArgumentException("Invalid GI capture quality or distance range");
            }
        }
    }

    /** Optional signed-SDF/application policy; write the relocated world position into store. */
    @FunctionalInterface
    public interface ProbeValidator {
        boolean place(Spatial scene, Vector3f position, Vector3f spacing, float clearance, Vector3f store);
    }

    /** Blocking convenience wrapper. Editors should use start() and step() each frame. */
    public static GiProbeData bake(RenderManager manager, Spatial scene, GiProbeVolume volume,
            Settings settings, Consumer<ViewPort> configureCapture, BooleanSupplier cancelled,
            IntConsumer completedProbes) {
        try (GiBakeJob job = start(manager, scene, volume, settings, configureCapture, cancelled, completedProbes)) {
            while (!job.step(Long.MAX_VALUE)) {
                // Synchronous compatibility entry point.
            }
            return job.getResult();
        }
    }

    /** Creates a render-thread job. See GiBakeJob for budgeting, cancellation and resume usage. */
    public static GiBakeJob start(RenderManager manager, Spatial scene, GiProbeVolume volume,
            Settings settings, Consumer<ViewPort> configureCapture, BooleanSupplier cancelled,
            IntConsumer completedProbes) {
        settings = settings.copy();
        settings.validate();
        java.util.Objects.requireNonNull(configureCapture);
        java.util.Objects.requireNonNull(cancelled);
        java.util.Objects.requireNonNull(completedProbes);
        Renderer renderer = manager.getRenderer();
        if (!renderer.getCaps().contains(Caps.FloatTexture)
                || !renderer.getCaps().contains(Caps.FloatColorBufferRGBA)) {
            throw new UnsupportedOperationException("GI baking requires float textures and RGBA float targets");
        }
        GiProbeGrid grid = volume.getBakeGrid();
        int count = grid.getProbeCount();
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        int limit = renderer.getLimits().get(Limits.TextureSize);
        long workingBytes = 6L * settings.captureSize * settings.captureSize * 20
                + (long) columns * rows * (512L + (settings.separateSky ? 256L : 0L) + 32L * settings.visibilityResolution * settings.visibilityResolution);
        if (count > settings.maxProbes || columns * Math.max(9, settings.visibilityResolution) > limit
                || rows * settings.visibilityResolution > limit || settings.captureSize > limit
                || workingBytes > settings.maxWorkingBytes) {
            throw new IllegalArgumentException("GI chunk exceeds probe/texture budget; split into smaller volumes");
        }
        if (manager.getForcedMaterial() != null || manager.getForcedTechnique() != null
                || manager.getForcedRenderState() != null) {
            throw new IllegalStateException("Bake outside a forced-material/technique/render-state pass");
        }
        return new GiBakeJob(manager, scene, volume, settings, configureCapture, cancelled, completedProbes);
    }

    static final class Capture {
        private final Settings settings;
        private final Camera camera;
        final ViewPort viewport;
        final FrameBuffer framebuffer;
        final ByteBuffer[] colors = new ByteBuffer[6];
        final ByteBuffer[] depths = new ByteBuffer[6];
        private final Vector3f[] forward = {Vector3f.UNIT_X, Vector3f.UNIT_X.negate(),
            Vector3f.UNIT_Y, Vector3f.UNIT_Y.negate(), Vector3f.UNIT_Z, Vector3f.UNIT_Z.negate()};
        private final Vector3f[] right = new Vector3f[6];
        private final Vector3f[] up = new Vector3f[6];

        Capture(Settings settings, Spatial scene) {
            this.settings = settings;
            camera = new Camera(settings.captureSize, settings.captureSize);
            camera.setFrustumPerspective(90, 1, settings.near, settings.far);
            viewport = new ViewPort("GI probe capture", camera);
            viewport.setRenderPath(RenderPath.Forward);
            viewport.setClearFlags(true, true, true);
            viewport.setBackgroundColor(settings.background);
            viewport.attachScene(scene);
            framebuffer = new FrameBuffer(settings.captureSize, settings.captureSize, 1);
            framebuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
            framebuffer.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
            viewport.setOutputFrameBuffer(framebuffer);
            for (int face = 0; face < 6; face++) {
                Vector3f hint = Math.abs(forward[face].y) > 0.5f ? Vector3f.UNIT_Z : Vector3f.UNIT_Y;
                camera.lookAtDirection(forward[face], hint);
                right[face] = camera.getLeft().negate();
                up[face] = camera.getUp().clone();
                colors[face] = BufferUtils.createByteBuffer(settings.captureSize * settings.captureSize * 16);
                depths[face] = BufferUtils.createByteBuffer(settings.captureSize * settings.captureSize * 4);
            }
        }

        void renderFace(RenderManager manager, Vector3f position, int face) {
            camera.setLocation(position);
            camera.setAxes(right[face].negate(), up[face], forward[face]);
            manager.renderViewPort(viewport, 0);
            colors[face].clear();
            depths[face].clear();
            manager.getRenderer().readFrameBufferWithFormat(framebuffer, colors[face], Image.Format.RGBA32F);
            manager.getRenderer().readFrameBufferWithFormat(framebuffer, depths[face], Image.Format.Depth32F);
        }

        private int face(Vector3f direction) {
            float x = Math.abs(direction.x);
            float y = Math.abs(direction.y);
            float z = Math.abs(direction.z);
            if (x >= y && x >= z) {
                return direction.x >= 0 ? 0 : 1;
            }
            if (y >= z) {
                return direction.y >= 0 ? 2 : 3;
            }
            return direction.z >= 0 ? 4 : 5;
        }

        private int pixel(Vector3f direction, int face) {
            float scale = direction.dot(forward[face]);
            int x = (int) ((direction.dot(right[face]) / scale * 0.5f + 0.5f) * settings.captureSize);
            int y = (int) ((direction.dot(up[face]) / scale * 0.5f + 0.5f) * settings.captureSize);
            x = Math.max(0, Math.min(settings.captureSize - 1, x));
            y = Math.max(0, Math.min(settings.captureSize - 1, y));
            return y * settings.captureSize + x;
        }

        void color(Vector3f direction, Vector3f result) {
            int face = face(direction);
            if (settings.separateSky && skyVisibility(direction) > 0.5f) {
                result.set(0, 0, 0);
                return;
            }
            int pixel = pixel(direction, face) * 16;
            result.set(Math.max(0, colors[face].getFloat(pixel)),
                    Math.max(0, colors[face].getFloat(pixel + 4)),
                    Math.max(0, colors[face].getFloat(pixel + 8)));
        }

        float skyVisibility(Vector3f direction) {
            int face = face(direction);
            return depths[face].getFloat(pixel(direction, face) * 4) >= 0.999999f ? 1f : 0f;
        }

        // Hardware depth is affine across a projected plane. Filtering depth
        // before unprojection reconstructs that plane instead of stamping the
        // nearest capture pixel into the visibility atlas. Never interpolate
        // across sky or a large depth discontinuity (which invents occluders).
        float distance(Vector3f direction) {
            int face = face(direction);
            float scale = direction.dot(forward[face]);
            float px = (direction.dot(right[face]) / scale * 0.5f + 0.5f) * settings.captureSize - 0.5f;
            float py = (direction.dot(up[face]) / scale * 0.5f + 0.5f) * settings.captureSize - 0.5f;
            int x = (int) Math.floor(px);
            int y = (int) Math.floor(py);
            float depth = depths[face].getFloat(pixel(direction, face) * 4);
            if (x >= 0 && y >= 0 && x + 1 < settings.captureSize && y + 1 < settings.captureSize) {
                float a = depths[face].getFloat((y * settings.captureSize + x) * 4);
                float b = depths[face].getFloat((y * settings.captureSize + x + 1) * 4);
                float c = depths[face].getFloat(((y + 1) * settings.captureSize + x) * 4);
                float d = depths[face].getFloat(((y + 1) * settings.captureSize + x + 1) * 4);
                float low = Math.min(Math.min(a, b), Math.min(c, d));
                float high = Math.max(Math.max(a, b), Math.max(c, d));
                if (high < 1 && axialDistance(high) <= axialDistance(low) * 1.25f) {
                    float u = px - x;
                    float v = py - y;
                    depth = (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
                }
            }
            if (depth >= 1) {
                return settings.far;
            }
            return Math.min(settings.far, axialDistance(depth) / scale);
        }

        private float axialDistance(float depth) {
            return settings.near * settings.far
                    / (settings.far - depth * (settings.far - settings.near));
        }

        void momentsRow(int y, float[] result) {
            int size = settings.visibilityResolution;
            Vector3f n = new Vector3f();
            Vector3f direction = new Vector3f();
            Vector3f tangent = new Vector3f();
            Vector3f bitangent = new Vector3f();
            for (int x = 0; x < size; x++) {
                float ox = 2f * (x + 0.5f) / size - 1;
                float oy = 2f * (y + 0.5f) / size - 1;
                n.set(ox, oy, 1 - Math.abs(ox) - Math.abs(oy));
                if (n.z < 0) {
                    n.x = (1 - Math.abs(oy)) * (ox >= 0 ? 1 : -1);
                    n.y = (1 - Math.abs(ox)) * (oy >= 0 ? 1 : -1);
                }
                n.normalizeLocal();
                n.cross(Math.abs(n.y) < 0.9f ? Vector3f.UNIT_Y : Vector3f.UNIT_X, tangent).normalizeLocal();
                n.cross(tangent, bitangent);
                double sum = 0;
                double square = 0;
                for (int sample = 0; sample < settings.visibilitySamples; sample++) {
                    double cosine = 1 - (sample + 0.5) / settings.visibilitySamples
                            * (1 - Math.cos(settings.visibilityCone));
                    double sine = Math.sqrt(1 - cosine * cosine);
                    double phi = sample * 2.399963229728653;
                    float u = (float) (sine * Math.cos(phi));
                    float v = (float) (sine * Math.sin(phi));
                    float w = (float) cosine;
                    direction.set(n.x * w + tangent.x * u + bitangent.x * v,
                            n.y * w + tangent.y * u + bitangent.y * v,
                            n.z * w + tangent.z * u + bitangent.z * v);
                    direction.normalizeLocal();
                    double distance = distance(direction);
                    sum += distance;
                    square += distance * distance;
                }
                int pixel = (y * size + x) * 2;
                result[pixel] = (float) (sum / settings.visibilitySamples);
                result[pixel + 1] = (float) (square / settings.visibilitySamples);
            }
        }
    }
}

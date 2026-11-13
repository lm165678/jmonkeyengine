package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.renderer.deferred.DeferredPipeline;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Large model-instance benchmark; defaults to 100,000 model roots, not triangles.
 * Boat, Buggy and Tank retain their meshes and materials. A model may contain
 * multiple geometry instances; the HUD reports both counts and indirect batches.
 * Requires desktop OpenGL 4.3 and testdata assets. No per-frame GPU readback is
 * performed. Frame times include CPU traversal, submission and presentation;
 * they are not isolated GPU timings. VSync is disabled; driver settings may override it.
 *
 * <p>Entry: TestGpuDrivenPerformance.main. Options: --count=300000,
 * --moving=1000, --deferred, --benchmark, --no-distance, --no-lod, --mdi.
 * I toggles shared-index LOD multi-draw (default off); this rebuilds registration. Space: GPU Scene; M: motion;
 * F2: distance; F3: frustum; F4: screen size; F5: current-frame Hi-Z; L: GPU LOD.
 * Drag + WASD: camera. Hi-Z is initially off, distance is 300 world units,
 * screen cutoff is 2 pixels, and frustum culling is enabled. Unsupported materials
 * remain on the original rendering path. Runtime switches run on the render thread.
 *
 * <p>Automated command: gradlew :jme3-examples:gpuDrivenPerformanceCheck
 * -PgpuInstances=100000 -PgpuMoving=1000. It warms up for 60 frames and measures
 * 600 stationary frames followed by 600 moving frames at the same camera pose.
 * Output reports mean/p50/p95/p99 milliseconds and input counts. It checks that
 * GPU registration works, but intentionally does not impose machine-specific FPS.
 * Large scenes require substantial Java heap as well as GPU memory; the task uses
 * an 8 GiB maximum heap. Loading/cloning is excluded from measured frames.
 * Add -PgpuProfile on JDK 11+ to record CPU/GC samples in .gradle/gpu-scene.jfr.
 * This scenario measures a large resident world with many rejected instances,
 * not 100,000 simultaneously visible models. Disable F2/F3/F4 interactively to
 * measure drawing pressure separately; keep the camera and settings identical
 * when comparing revisions. The flat scene also includes ordinary scene-graph
 * transform/bound updates in the moving measurements.
 * Use -PgpuNoDistance for the same automated camera without distance culling.
 * Visibility/triangle counts are read only after each timing interval; this
 * diagnostic synchronization is excluded from subsequent timing samples.
 * Run bakeGpuDrivenLods first. This example loads the new Lod*.j3o assets and
 * enables 80/30/10/3 pixel LOD thresholds by default. Use -PgpuNoLod for LOD0
 * comparisons using the same baked meshes. Missing levels clamp to the last
 * available index buffer. LOD changes geometry only, preserving original materials.
 * Use -PgpuLinearUpdates to disable the Node update index for an otherwise identical
 * control run. CPU_PHASE reports application-update and scene-update wall time;
 * it is separate from GPU timing. -PgpuMeasureFrames=120 changes each interval
 * (minimum 120). The index does not reorganize children or reduce mover count.
 *
 * <pre>{@code
 * // 第一步：加载真实资产，clone(false) 共享网格/材质，避免复制纹理和网格。
 * Spatial source = assetManager.loadModel("Models/Tank/tank.j3o");
 * for (int i = 0; i < 100000; i++) {
 *     Spatial instance = source.clone(false);
 *     instance.setLocalTranslation((i % 400) * 8, 0, (i / 400) * 8);
 *     models.attachChild(instance);
 * }
 * // 第二步：选择着色路径，再用 GPU Scene 包装；不替换原材质的着色逻辑。
 * GpuScenePipeline gpu = new GpuScenePipeline(assetManager, models, new ForwardPipeline());
 * renderManager.setPipeline(viewPort, gpu);
 * gpu.setEnabled(true);
 * // 普通 Node 根自动启用增量更新；关闭 GPU Scene 时恢复原来的索引开关。
 * // 单独使用场景图优化也可 models.setIncrementalUpdatesEnabled(true)。
 * gpu.setMaxDistance(300);
 * gpu.setMinPixels(2);
 * // 第三步：先固定相机测静态场景，再仅改变运动开关比较，不同时改变剔除设置。
 * // HUD 读取 getInstanceCount/getBatchCount 不会等待 GPU。
 * // readVisibleCount 是阻塞诊断接口，不要放到性能测试每帧循环中。
 * // 第四步：需要遮挡测试时开启 Hi-Z，它会增加当前帧深度预绘制的成本。
 * gpu.setOcclusionCulling(true);
 * // 第五步：关闭 GPU Scene 恢复同一着色路径；移除包装器时引擎负责清理。
 * gpu.setEnabled(false);
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class TestGpuDrivenPerformance extends SimpleApplication {
    private final Node models = new Node("Benchmark models");
    private int count = 100000;
    private int moving = 1000;
    private boolean benchmark;
    private boolean deferred;
    private boolean motion;
    private boolean distance = true;
    private boolean frustum = true;
    private boolean screen = true;
    private boolean hiz;
    private boolean lod = true;
    private boolean linearUpdates;
    private boolean mdi;
    private int measureFrames = 600;
    private Spatial[] movers;
    private GpuScenePipeline pipeline;
    private int frame;
    private long lastFrame;
    private double[] samples;
    private double[] sceneSamples;
    private double[] updateSamples;
    private float hudTime;
    private BitmapText hud;

    public static void main(String[] args) {
        TestGpuDrivenPerformance app = new TestGpuDrivenPerformance();
        for (String arg : args) {
            if (arg.startsWith("--count=")) {
                app.count = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--moving=")) {
                app.moving = Integer.parseInt(arg.substring(9));
            } else if (arg.equals("--mdi")) {
                app.mdi = true;
            } else if (arg.equals("--benchmark")) {
                app.benchmark = true;
            } else if (arg.equals("--deferred")) {
                app.deferred = true;
            } else if (arg.equals("--no-distance")) {
                app.distance = false;
            } else if (arg.equals("--no-lod")) {
                app.lod = false;
            } else if (arg.equals("--linear-updates")) {
                app.linearUpdates = true;
            } else if (arg.startsWith("--frames=")) {
                app.measureFrames = Integer.parseInt(arg.substring(9));
            }
        }
        if (app.count < 1 || app.moving < 0 || app.moving > app.count || app.measureFrames < 120) {
            throw new IllegalArgumentException("Require count > 0 and 0 <= moving <= count");
        }
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(1280, 720);
        settings.setVSync(false);
        settings.setAudioRenderer(null);
        settings.setTitle("GPU Scene / 100k+ model benchmark");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        samples = new double[measureFrames];
        sceneSamples = new double[measureFrames];
        updateSamples = new double[measureFrames];
        if (benchmark) {
            setAppProfiler(new com.jme3.profile.AppProfiler() {
                private long updateStart;
                private long sceneStart;

                @Override
                public void appStep(com.jme3.profile.AppStep step) {
                    long now = System.nanoTime();
                    if (step == com.jme3.profile.AppStep.StateManagerUpdate) {
                        updateStart = now;
                    } else if (step == com.jme3.profile.AppStep.SpatialUpdate) {
                        sceneStart = now;
                        if (frame >= 60) {
                            updateSamples[(frame - 60) % measureFrames] = (now - updateStart) / 1000000.0;
                        }
                    } else if (step == com.jme3.profile.AppStep.StateManagerRender && frame >= 60) {
                        sceneSamples[(frame - 60) % measureFrames] = (now - sceneStart) / 1000000.0;
                    }
                }

                @Override
                public void appSubStep(String... info) {
                }

                @Override
                public void vpStep(com.jme3.profile.VpStep step, com.jme3.renderer.ViewPort viewport,
                        com.jme3.renderer.queue.RenderQueue.Bucket bucket) {
                }

                @Override
                public void spStep(com.jme3.profile.SpStep step, String... info) {
                }
            });
        }
        hud = new BitmapText(guiFont);
        hud.setLocalTranslation(10, cam.getHeight() - 10, 0);
        guiNode.attachChild(hud);
        rootNode.attachChild(models);
        String[] paths = {"Models/Tank/LodTank.j3o", "Models/Boat/LodBoat.j3o", "Models/Buggy/LodBuggy.j3o"};
        Spatial[] sources = new Spatial[paths.length];
        float[] scales = new float[paths.length];
        for (int i = 0; i < paths.length; i++) {
            sources[i] = assetManager.loadModel(paths[i]);
            sources[i].updateGeometricState();
            scales[i] = 2 / ((BoundingBox) sources[i].getWorldBound()).getExtent(null).length();
        }
        movers = new Spatial[moving];
        int columns = (int) Math.ceil(Math.sqrt(count));
        Random random = new Random(76319);
        for (int i = 0; i < count; i++) {
            int type = i % sources.length;
            Spatial model = sources[type].clone(false);
            float scale = scales[type];
            model.setLocalScale(scale * (.8f + random.nextFloat() * .4f),
                    scale * (.8f + random.nextFloat() * .4f),
                    scale * (.8f + random.nextFloat() * .4f));
            model.rotate(0, random.nextFloat() * 6.283185f, 0);
            model.setLocalTranslation((i % columns - columns / 2) * 8, 0, -(i / columns) * 8);
            models.attachChild(model);
            // Spread movers across the whole world instead of putting them in one row.
            if (moving > 0 && (long) i * moving / count != (long) (i + 1) * moving / count) {
                movers[(int) ((long) i * moving / count)] = model;
            }
        }
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal()));
        cam.setLocation(new Vector3f(0, 30, 45));
        cam.lookAt(new Vector3f(0, 0, -100), Vector3f.UNIT_Y);
        cam.setFrustumFar(4000);
        flyCam.setMoveSpeed(100);
        flyCam.setDragToRotate(true);
        pipeline = new GpuScenePipeline(assetManager, models, deferred
                ? new DeferredPipeline(assetManager, renderManager.getDeferredShadingModels())
                : new ForwardPipeline());
        renderManager.setPipeline(viewPort, pipeline);
        pipeline.setEnabled(true);
        pipeline.setMultiDrawIndirect(mdi);
        if (linearUpdates) {
            models.setIncrementalUpdatesEnabled(false);
        }
        pipeline.setMaxDistance(distance ? 300 : 0);
        pipeline.setMinPixels(2);
        pipeline.setLodThresholds(lod ? new float[]{80, 30, 10, 3} : new float[0]);
        String[] names = {"GPU", "Motion", "Distance", "Frustum", "Screen", "HiZ", "LOD", "MDI"};
        int[] keys = {KeyInput.KEY_SPACE, KeyInput.KEY_M, KeyInput.KEY_F2,
            KeyInput.KEY_F3, KeyInput.KEY_F4, KeyInput.KEY_F5, KeyInput.KEY_L, KeyInput.KEY_I};
        for (int i = 0; i < names.length; i++) {
            inputManager.addMapping(names[i], new KeyTrigger(keys[i]));
        }
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed || benchmark) {
                return;
            }
            switch (name) {
                case "GPU":
                    pipeline.setEnabled(!pipeline.isEnabled());
                    break;
                case "Motion":
                    motion = !motion;
                    break;
                case "Distance":
                    distance = !distance;
                    pipeline.setMaxDistance(distance ? 300 : 0);
                    break;
                case "Frustum":
                    frustum = !frustum;
                    pipeline.setFrustumCulling(frustum);
                    break;
                case "Screen":
                    screen = !screen;
                    pipeline.setMinPixels(screen ? 2 : 0);
                    break;
                case "HiZ":
                    hiz = !hiz;
                    pipeline.setOcclusionCulling(hiz);
                    break;
                case "MDI":
                    mdi = !mdi;
                    pipeline.setMultiDrawIndirect(mdi);
                    break;
                case "LOD":
                    lod = !lod;
                    pipeline.setLodThresholds(lod ? new float[]{80, 30, 10, 3} : new float[0]);
                    break;
                default:
                    break;
            }
        }, names);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (motion) {
            for (Spatial mover : movers) {
                mover.rotate(0, tpf * .5f, 0);
            }
        }
        hudTime += tpf;
        if (hudTime > .5f) {
            hudTime = 0;
            hud.setText(String.format(Locale.ROOT,
                    "models=%d geometries=%d batches=%d frame=%.2f ms%nGPU=%s motion=%s (%d) F2 distance=%s F3 frustum=%s F4 size=%s F5 HiZ=%s L lod=%s I mdi=%s",
                    count, pipeline.getInstanceCount(), pipeline.getBatchCount(), tpf * 1000,
                    pipeline.isActive(), motion, moving, distance, frustum, screen, hiz, lod, mdi));
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!benchmark) {
            return;
        }
        long now = System.nanoTime();
        frame++;
        int staticEnd = 60 + measureFrames;
        int movingEnd = staticEnd + measureFrames;
        if (frame > 60 && frame <= movingEnd) {
            samples[(frame - 61) % samples.length] = (now - lastFrame) / 1000000.0;
        }
        lastFrame = now;
        if (frame == staticEnd || frame == movingEnd) {
            if (!pipeline.isActive() || pipeline.getInstanceCount() < count) {
                throw new AssertionError("GPU model registration incomplete");
            }
            double[] ordered = samples.clone();
            Arrays.sort(ordered);
            double mean = Arrays.stream(ordered).average().getAsDouble();
            System.out.printf(Locale.ROOT,
                    "GPU_BENCH mdi=" + mdi + " models=%d geometries=%d batches=%d motion=%s mean=%.3f p50=%.3f p95=%.3f p99=%.3f ms%n",
                    count, pipeline.getInstanceCount(), pipeline.getBatchCount(), motion,
                    mean, ordered[(int) (measureFrames * .5) - 1],
                    ordered[(int) (measureFrames * .95) - 1], ordered[(int) (measureFrames * .99) - 1]);
            System.out.printf(Locale.ROOT, "CPU_PHASE indexed=%s update=%.3f scene=%.3f ms%n",
                    models.isIncrementalUpdatesEnabled(), Arrays.stream(updateSamples).average().getAsDouble(),
                    Arrays.stream(sceneSamples).average().getAsDouble());
            motion = true;
            System.out.printf(Locale.ROOT, "GPU_WORK distance=%s lod=%s visible=%d triangles=%d%n",
                    distance, lod, pipeline.readVisibleCount(), pipeline.readVisibleTriangleCount());
            if (frame == staticEnd) {
                long expected = pipeline.readVisibleTriangleCount();
                long sampled = renderer.getStatistics().getTriangleCount();
                // The whole-frame sample also includes the HUD, unlike the scene-only diagnostic.
                if (sampled < expected || sampled > expected + 100000) {
                    throw new AssertionError("Indirect HUD statistics mismatch: " + sampled + " versus " + expected);
                }
                System.out.println("GPU_STATS_PASS triangles=" + sampled + " age="
                        + renderer.getStatistics().getGeometrySampleAge());
            }
            lastFrame = System.nanoTime();
        }
        if (frame == movingEnd) {
            System.out.println("GPU_PERFORMANCE_PASS");
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (benchmark) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }
}

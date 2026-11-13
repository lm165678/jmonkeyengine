package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.renderer.gpuscene.HiZStatistics;
import com.jme3.renderer.gpuscene.HiZMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import java.util.Locale;

/**
 * Dedicated Hi-Z diagnostic scene: a generated wall occludes 1,200 real Tank,
 * Boat and Buggy model roots. Models retain their original shading materials.
 * Optional --mesh-merging exercises packed cross-mesh MDI with Hi-Z and history.
 * --compaction also enables GPU command-count submission; gpuHiZCompactSmoke
 * verifies this combination including frozen culling and historical depth.
 * Requires desktop OpenGL 4.3. Entry: TestHiZOcclusion.main; automated verification:
 * gradlew :jme3-examples:gpuHiZSmoke. Originals are loaded; no baking prerequisite.
 *
 * <p>H toggles Hi-Z; O moves the wall aside/restores it; V visualizes the pyramid;
 * PageUp/PageDown selects a level; F6 toggles diagnostics; F freezes GPU visibility
 * and transforms while allowing camera inspection. Drag+WASD moves camera.
 * T switches SAME_FRAME/HISTORY_TWO_PHASE. History selects occluders only;
 * current depth retests all candidates. A reduced prepass is still required to
 * respect the occluder filter; this is not unfiltered final-depth reuse.
 * B toggles the wall's occluder role; Q toggles model occlusion queries. Both
 * default on; only the wall supplies occluder depth in this example. These
 * policies affect this main viewport, not shadow-map rendering.
 * Q skips the second query/compaction dispatch, but retains pyramid construction
 * for visualization. Occlusion sets instance counts to zero; it does not remove
 * CPU draw submissions. Small/cheap scenes can therefore be faster without Q.
 * Compare whole-frame milliseconds at the same camera, with freeze disabled.
 * Depth is standard-Z, max-reduced. Visualization uses logarithmic linear distance
 * (blue near, yellow far, dark background), not raw near-one hardware depth.
 * Level zero is the depth prepass. Diagnostics update at most four times/second,
 * asynchronously; pending/stale samples are explicitly labeled. Counts are geometry
 * instances, not model roots. GPU cost excludes diagnostic copies and final shading;
 * compare overall frame time with H to decide whether occlusion pays for itself.
 * Frozen mode intentionally skips culling/depth work: its displayed GPU sample is
 * the capture frame, not current-frame timing. It is an inspection tool, not a benchmark mode.
 * Do not enable DetailedProfiler simultaneously: elapsed-time queries cannot nest.
 *
 * <pre>{@code
 * // 第一步：建立明确的大遮挡体，普通 Lighting 材质提供 PreShadow 深度技术。
 * Geometry wall = new Geometry("Occluder wall", new Box(110, 50, 1));
 * wall.setMaterial(wallMaterial);
 * models.attachChild(wall);
 * // 第二步：墙后摆放真实模型，创建 GPU Scene 并打开 Hi-Z。
 * GpuScenePipeline gpu = new GpuScenePipeline(assetManager, models);
 * renderManager.setPipeline(viewPort, gpu);
 * gpu.setEnabled(true);
 * gpu.setOcclusionCulling(true);
 * gpu.setHiZDiagnosticsEnabled(true);
 * // 对象角色独立：只让墙提供遮挡深度，墙本身不需要接受遮挡查询。
 * gpu.setOccluderFilter(g -> g == wall);
 * gpu.setOcclusionQueryFilter(g -> g != wall);
 * // 恢复默认：传入 null；捕获变量或 Geometry 标记变化后调用 gpu.rebuild()。
 * // 两阶段可选：历史只筛选遮挡体，最终可见性必须通过当前深度补测。
 * gpu.setHiZMode(HiZMode.HISTORY_TWO_PHASE);
 * // 首帧/resize/rebuild 没有历史时自动用完整候选；恢复默认用 SAME_FRAME。
 * gpu.setHiZMode(HiZMode.SAME_FRAME);
 * // 第三步：HUD 读取已完成的异步样本；不调用阻塞 readVisibleCount。
 * HiZStatistics sample = gpu.getHiZStatistics();
 * if (sample != null) {
 *     long occluded = sample.getOccluded();
 *     double depthMs = sample.getDepthMilliseconds();
 *     double pyramidMs = sample.getPyramidMilliseconds();
 *     double cullMs = sample.getCullingMilliseconds();
 * }
 * // 第四步：在 GUI render 回调中重新取得借用纹理，防止 resize 后使用旧资源。
 * debugMaterial.setTexture("Depth", gpu.getHiZLevel(level));
 * // 第五步：移动墙体对照剔除数量；关闭 H 对照总帧耗时。
 * wall.setLocalTranslation(500, 10, 0);
 * // 冻结检查：下一帧捕获结果，然后可自由移动相机观察；解除后处理累积变化。
 * gpu.setCullingFrozen(true);
 * gpu.setCullingFrozen(false);
 * // 第六步：退出时由管线 cleanup 释放金字塔、查询和异步快照，勿销毁借用纹理。
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class TestHiZOcclusion extends SimpleApplication {
    private final Node models = new Node("HiZ test scene");
    private GpuScenePipeline pipeline;
    private Geometry wall;
    private Geometry preview;
    private Material debug;
    private BitmapText hud;
    private boolean hiz = true;
    private boolean diagnostics = true;
    private boolean wallAside;
    private boolean wallOccluder = true;
    private boolean modelQueries = true;
    private boolean visualize;
    private boolean frozen;
    private com.jme3.renderer.Camera captureCamera;
    private int level;
    private float elapsed;
    private float hudTimer;
    private boolean smoke;
    private boolean meshMerging;
    private boolean compaction;
    private int stage;
    private int previewFrames;
    private long occludedWithWall;
    private long fullDepthTriangles;
    private float stageStarted;
    private com.jme3.texture.Texture2D frozenDepth;
    private long frozenVisible;

    public static void main(String[] args) {
        TestHiZOcclusion app = new TestHiZOcclusion();
        app.compaction = java.util.Arrays.asList(args).contains("--compaction");
        app.meshMerging = app.compaction || java.util.Arrays.asList(args).contains("--mesh-merging");
        app.smoke = java.util.Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(1000, 700);
        settings.setVSync(false);
        settings.setAudioRenderer(null);
        settings.setTitle("Hi-Z diagnostic / generated wall and real models");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        rootNode.attachChild(models);
        String[] paths = {"Models/Tank/tank.j3o", "Models/Boat/boat.j3o", "Models/Buggy/Buggy.j3o"};
        for (int type = 0; type < paths.length; type++) {
            Spatial source = assetManager.loadModel(paths[type]);
            source.updateGeometricState();
            float radius = ((BoundingBox) source.getWorldBound()).getExtent(null).length();
            for (int i = 0; i < 400; i++) {
                Spatial instance = source.clone(false);
                instance.setLocalScale(2 / radius);
                instance.setLocalTranslation((i % 20 - 10) * 6, type * 5, -20 - (i / 20) * 8);
                instance.rotate(0, i * .17f, 0);
                models.attachChild(instance);
            }
        }
        wall = new Geometry("Generated occluder wall", new Box(110, 50, 1));
        Material wallMaterial = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        wallMaterial.setBoolean("UseMaterialColors", true);
        wallMaterial.setColor("Diffuse", new ColorRGBA(.45f, .3f, .18f, 1));
        wallMaterial.setColor("Ambient", ColorRGBA.White);
        wall.setMaterial(wallMaterial);
        wall.setLocalTranslation(0, 10, 0);
        models.attachChild(wall);
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal()));
        rootNode.addLight(new AmbientLight(new ColorRGBA(.2f, .2f, .2f, 1)));
        cam.setLocation(new Vector3f(0, 15, 70));
        cam.lookAt(new Vector3f(0, 8, -80), Vector3f.UNIT_Y);
        cam.setFrustumFar(500);
        flyCam.setDragToRotate(true);
        flyCam.setMoveSpeed(30);
        pipeline = new GpuScenePipeline(assetManager, models);
        renderManager.setPipeline(viewPort, pipeline);
        pipeline.setEnabled(true);
        pipeline.setMultiDrawIndirect(meshMerging);
        pipeline.setMeshMerging(meshMerging);
        pipeline.setCommandCompaction(compaction);
        pipeline.setOcclusionCulling(true);
        pipeline.setHiZDiagnosticsEnabled(true);
        // 第一步：墙作为遮挡体，真实模型只接受查询，避免全场景深度预绘制。
        pipeline.setOccluderFilter(geometry -> geometry == wall);
        // 第二步：墙不接受 Hi-Z 查询，但仍接受视锥、距离等常规 GPU 剔除。
        pipeline.setOcclusionQueryFilter(geometry -> geometry != wall);
        debug = new Material(assetManager, "Common/MatDefs/GpuScene/HiZDebug.j3md");
        debug.setFloat("DisplayRange", 300);
        preview = new Geometry("Hi-Z level preview", new Quad(1, 1));
        preview.setMaterial(debug);
        preview.setLocalTranslation(0, 0, -1);
        preview.setCullHint(Spatial.CullHint.Always);
        preview.addControl(new AbstractControl() {
            @Override
            protected void controlUpdate(float tpf) {
            }

            @Override
            protected void controlRender(RenderManager manager, ViewPort viewport) {
                int levels = pipeline.getHiZLevelCount();
                if (levels > 0) {
                    level = Math.min(level, levels - 1);
                    debug.setTexture("Depth", pipeline.getHiZLevel(level));
                    if (frozen && captureCamera == null && pipeline.isCullingSnapshotFrozen()) {
                        captureCamera = pipeline.getFrozenCullingCamera();
                    }
                    com.jme3.renderer.Camera depthCamera = frozen && captureCamera != null ? captureCamera : cam;
                    debug.setFloat("Near", depthCamera.getFrustumNear());
                    debug.setFloat("Far", depthCamera.getFrustumFar());
                }
            }
        });
        guiNode.attachChild(preview);
        hud = new BitmapText(guiFont);
        guiNode.attachChild(hud);
        String[] names = {"HiZ", "Wall", "Visualize", "Next", "Previous", "Diagnostics", "Freeze", "OccluderRole", "QueryRole", "History"};
        int[] keys = {KeyInput.KEY_H, KeyInput.KEY_O, KeyInput.KEY_V,
            KeyInput.KEY_PGUP, KeyInput.KEY_PGDN, KeyInput.KEY_F6, KeyInput.KEY_F, KeyInput.KEY_B, KeyInput.KEY_Q, KeyInput.KEY_T};
        for (int i = 0; i < names.length; i++) {
            inputManager.addMapping(names[i], new KeyTrigger(keys[i]));
        }
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed || smoke) {
                return;
            }
            switch (name) {
                case "HiZ":
                    hiz = !hiz;
                    pipeline.setOcclusionCulling(hiz);
                    frozen = false;
                    captureCamera = null;
                    break;
                case "History":
                    captureCamera = null;
                    pipeline.setHiZMode(pipeline.getHiZMode() == HiZMode.SAME_FRAME
                            ? HiZMode.HISTORY_TWO_PHASE : HiZMode.SAME_FRAME);
                    break;
                case "OccluderRole":
                    captureCamera = null;
                    wallOccluder = !wallOccluder;
                    pipeline.setOccluderFilter(geometry -> wallOccluder && geometry == wall);
                    break;
                case "QueryRole":
                    captureCamera = null;
                    modelQueries = !modelQueries;
                    pipeline.setOcclusionQueryFilter(geometry -> modelQueries && geometry != wall);
                    break;
                case "Wall":
                    moveWall(!wallAside);
                    break;
                case "Visualize":
                    visualize = !visualize;
                    break;
                case "Next":
                    level = Math.min(level + 1, Math.max(0, pipeline.getHiZLevelCount() - 1));
                    break;
                case "Previous":
                    level = Math.max(0, level - 1);
                    break;
                case "Diagnostics":
                    diagnostics = !diagnostics;
                    pipeline.setHiZDiagnosticsEnabled(diagnostics);
                    break;
                case "Freeze":
                    frozen = !frozen;
                    captureCamera = null;
                    pipeline.setCullingFrozen(frozen);
                    break;
                default:
                    break;
            }
        }, names);
    }

    private void moveWall(boolean aside) {
        wallAside = aside;
        wall.setLocalTranslation(aside ? 500 : 0, 10, 0);
    }

    @Override
    public void simpleUpdate(float tpf) {
        elapsed += tpf;
        hudTimer += tpf;
        preview.setLocalScale(cam.getWidth(), cam.getHeight(), 1);
        preview.setCullHint(visualize && hiz && pipeline.getHiZLevelCount() > 0
                ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        hud.setLocalTranslation(10, cam.getHeight() - 10, 2);
        if (hudTimer >= .2f) {
            hudTimer = 0;
            HiZStatistics sample = pipeline.getHiZStatistics();
            String values = sample == null ? "Hi-Z sample: pending/off (no CPU wait)"
                    : String.format(Locale.ROOT,
                            "candidates=%d survivors=%d Hi-Z rejected=%d (%.1f%%) age=%.0f ms%n"
                            + "GPU depth=%.3f pyramid=%.3f cull=%.3f history=%.3f total=%.3f ms%n"
                            + "Triangles: depth prepass=%d | final scene commands=%d (bottom-left includes ALL passes)",
                            sample.getCandidates(), sample.getSurvivors(), sample.getOccluded(),
                            sample.getCandidates() == 0 ? 0 : 100.0 * sample.getOccluded() / sample.getCandidates(),
                            sample.getAgeMilliseconds(), sample.getDepthMilliseconds(), sample.getPyramidMilliseconds(),
                            sample.getCullingMilliseconds(), sample.getHistoryMilliseconds(), sample.getTotalMilliseconds(),
                            sample.getDepthTriangles(), sample.getSurvivingTriangles());
            String size = pipeline.getHiZLevelCount() == 0 ? "unavailable"
                    : pipeline.getHiZLevel(Math.min(level, pipeline.getHiZLevelCount() - 1)).getImage().getWidth()
                    + "x" + pipeline.getHiZLevel(Math.min(level, pipeline.getHiZLevelCount() - 1)).getImage().getHeight();
            hud.setText(String.format(Locale.ROOT,
                    "H Hi-Z=%s | O wall aside=%s | V visualize=%s | PgUp/PgDn level=%d (%s) | F6 diagnostics=%s%n"
                    + "F frozen=%s (camera remains free) | frame=%.2f ms | B wall occluder=%s Q model queries=%s | T mode=%s%n%s",
                    hiz, wallAside, visualize, level, size, diagnostics, pipeline.isCullingSnapshotFrozen(), tpf * 1000, wallOccluder, modelQueries, pipeline.getHiZMode(), values));
        }
        if (smoke && elapsed > 20) {
            throw new AssertionError("Hi-Z diagnostic timeout, stage=" + stage);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!smoke) {
            return;
        }
        HiZStatistics sample = pipeline.getHiZStatistics();
        if (stage == 0 && sample != null) {
            if (sample.getCandidates() < 100 || sample.getOccluded() < sample.getCandidates() / 2
                    || !Double.isFinite(sample.getTotalMilliseconds()) || sample.getTotalMilliseconds() <= 0) {
                throw new AssertionError("Wall failed Hi-Z test: candidates=" + sample.getCandidates()
                        + " rejected=" + sample.getOccluded() + " GPU ms=" + sample.getTotalMilliseconds());
            }
            occludedWithWall = sample.getOccluded();
            System.out.println("HIZ_WALL_PASS before=" + sample.getCandidates() + " after=" + sample.getSurvivors()
                    + " GPU ms=" + sample.getTotalMilliseconds());
            frozen = true;
            pipeline.setCullingFrozen(true);
            stageStarted = elapsed;
            stage = 1;
        } else if (stage == 1 && pipeline.isCullingSnapshotFrozen() && sample != null) {
            frozenDepth = pipeline.getHiZLevel(0);
            frozenVisible = pipeline.readVisibleCount();
            if (sample.getSurvivors() != frozenVisible || sample.getSurvivingTriangles() != pipeline.readVisibleTriangleCount()) {
                throw new AssertionError("Frozen diagnostics do not match the captured frame");
            }
            moveWall(true);
            cam.setLocation(new Vector3f(160, 30, 40));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            stageStarted = elapsed;
            stage = 2;
        } else if (stage == 2 && elapsed - stageStarted > .2f) {
            if (pipeline.getHiZLevel(0) != frozenDepth || pipeline.readVisibleCount() != frozenVisible) {
                throw new AssertionError("Frozen visibility changed with camera or wall movement");
            }
            System.out.println("HIZ_FREEZE_PASS visible=" + frozenVisible);
            frozen = false;
            pipeline.setCullingFrozen(false);
            cam.setLocation(new Vector3f(0, 15, 70));
            cam.lookAt(new Vector3f(0, 8, -80), Vector3f.UNIT_Y);
            visualize = true;
            level = 2;
            stageStarted = elapsed;
            stage = 3;
        } else if (stage == 3 && sample != null) {
            if (sample.getOccluded() >= occludedWithWall || sample.getSurvivors() <= 0) {
                throw new AssertionError("Moving the wall did not reveal the models");
            }
            if (++previewFrames > 10 && elapsed - stageStarted > .4f) {
                System.out.println("HIZ_WALL_ASIDE_PASS rejected=" + sample.getOccluded());
                String image = System.getProperty("hizDebugImage");
                if (image != null) {
                    savePreview(image);
                }
                hiz = false;
                pipeline.setOcclusionCulling(false);
                stage = 4;
            }
        } else if (stage == 4) {
            if (pipeline.getHiZLevelCount() != 0 || pipeline.getHiZStatistics() != null) {
                throw new AssertionError("Hi-Z disable did not release diagnostics/pyramid");
            }
            // Leave a live diagnostic owner at shutdown to exercise LWJGL 2's
            // renderer-before-application cleanup order, rather than hiding it.
            hiz = true;
            pipeline.setOcclusionCulling(true);
            stage = 5;
        } else if (stage == 5 && sample != null) {
            moveWall(false);
            pipeline.setOccluderFilter(geometry -> false);
            stage = 6;
        } else if (stage == 6 && sample != null) {
            if (sample.getDepthTriangles() != 0 || sample.getOccluded() != 0) {
                throw new AssertionError("Disabled occluders still wrote depth or hid geometry");
            }
            pipeline.setOccluderFilter(geometry -> geometry == wall);
            pipeline.setOcclusionQueryFilter(geometry -> false);
            stage = 7;
        } else if (stage == 7 && sample != null) {
            if (sample.getDepthTriangles() <= 0 || sample.getOccluded() != 0) {
                throw new AssertionError("Query bypass incorrectly hid geometry or disabled occluders");
            }
            pipeline.setOccluderFilter(null);
            pipeline.setOcclusionQueryFilter(null);
            stage = 8;
        } else if (stage == 8 && sample != null) {
            if (sample.getOccluded() < sample.getCandidates() / 2) {
                throw new AssertionError("Restoring default policies failed");
            }
            System.out.println("HIZ_POLICY_PASS independent roles, query bypass and defaults verified");
            fullDepthTriangles = sample.getDepthTriangles();
            pipeline.setHiZMode(HiZMode.HISTORY_TWO_PHASE);
            stage = 9;
        } else if (stage == 9 && sample != null) {
            if (sample.getOccluded() < sample.getCandidates() / 2) {
                throw new AssertionError("Historical occluders failed to hide models behind wall");
            }
            if (sample.getDepthTriangles() >= fullDepthTriangles) {
                throw new AssertionError("History failed to reduce occluder prepass triangles");
            }
            System.out.println("HIZ_HISTORY_DEPTH_PASS triangles=" + fullDepthTriangles
                    + " -> " + sample.getDepthTriangles());
            moveWall(true);
            stage = 10;
        } else if (stage == 10) {
            // Check the very first rendered frame after motion, not a delayed sample.
            if (pipeline.readVisibleCount() < 1000) {
                throw new AssertionError("History hid models after moving occluder away");
            }
            cam.setLocation(new Vector3f(10, 20, 65));
            cam.lookAt(new Vector3f(0, 8, -80), Vector3f.UNIT_Y);
            stage = 11;
        } else if (stage == 11) {
            if (pipeline.readVisibleCount() < 1000) {
                throw new AssertionError("History failed after camera movement");
            }
            pipeline.setOcclusionCulling(false);
            stage = 12;
        } else if (stage == 12) {
            if (pipeline.getHiZLevelCount() != 0 || pipeline.getHiZStatistics() != null) {
                throw new AssertionError("Historical mode retained Hi-Z work while disabled");
            }
            pipeline.setOcclusionCulling(true);
            stage = 13;
        } else if (stage == 13 && sample != null) {
            System.out.println("HIZ_HISTORY_PASS wall motion, camera motion, disable and history fallback verified");
            System.out.println("HIZ_DIAGNOSTICS_PASS freeze, visualization, disable and live shutdown verified");
            stop();
        }
    }

    private void savePreview(String path) {
        java.nio.ByteBuffer pixels = com.jme3.util.BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBufferWithFormat(null, pixels, com.jme3.texture.Image.Format.RGBA8);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(cam.getWidth(), cam.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < cam.getHeight(); y++) {
            for (int x = 0; x < cam.getWidth(); x++) {
                int offset = (y * cam.getWidth() + x) * 4;
                int color = 0xFF000000 | ((pixels.get(offset) & 255) << 16)
                        | ((pixels.get(offset + 1) & 255) << 8) | (pixels.get(offset + 2) & 255);
                image.setRGB(x, cam.getHeight() - 1 - y, color);
            }
        }
        try {
            javax.imageio.ImageIO.write(image, "png", new java.io.File(path));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Failed to save diagnostic preview", failure);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }
}

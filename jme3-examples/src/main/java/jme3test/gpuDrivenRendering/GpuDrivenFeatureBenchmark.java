package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.deferred.DeferredPipeline;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Shared deterministic fixture; launch TestBindlessRendering or TestMultiDrawIndirect. */
final class GpuDrivenFeatureBenchmark extends SimpleApplication {
    private final boolean testBindless;
    private final Node models = new Node("300k model benchmark");
    private int count = 300000;
    private int measureFrames = 180;
    private boolean benchmark;
    private boolean deferred;
    private boolean meshMerging;
    private boolean compaction;
    private boolean materialMerging;
    private boolean withoutCount;
    private int referenceCommands;
    private final Material[] sharedMaterials = new Material[32];
    private boolean feature = true;
    private boolean motion;
    private int phase;
    private int phaseFrame;
    private long lastFrame;
    private double[] times;
    private Spatial[] movers;
    private Quaternion[] initialRotations;
    private GpuScenePipeline pipeline;
    private BitmapText hud;
    private ByteBuffer reference;
    private long referenceVisible;
    private long referenceTriangles;
    private int referenceDraws;

    private GpuDrivenFeatureBenchmark(boolean testBindless) {
        this.testBindless = testBindless;
    }

    static void launch(boolean bindless, String[] args) {
        GpuDrivenFeatureBenchmark app = new GpuDrivenFeatureBenchmark(bindless);
        for (String arg : args) {
            if (arg.startsWith("--count=")) {
                app.count = Integer.parseInt(arg.substring(8));
            } else if (arg.startsWith("--frames=")) {
                app.measureFrames = Integer.parseInt(arg.substring(9));
            } else if (arg.equals("--without-count")) {
                app.withoutCount = true;
            } else if (arg.equals("--material-merging")) {
                app.materialMerging = true;
                app.meshMerging = true;
            } else if (arg.equals("--compaction")) {
                app.compaction = true;
                app.meshMerging = true;
            } else if (arg.equals("--mesh-merging")) {
                app.meshMerging = true;
            } else if (arg.equals("--benchmark")) {
                app.benchmark = true;
            } else if (arg.equals("--deferred")) {
                app.deferred = true;
            }
        }
        if (app.count < 100000 || app.measureFrames < 120) {
            throw new IllegalArgumentException("Require at least 100000 models and 120 measured frames");
        }
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(1280, 720);
        settings.setVSync(false);
        settings.setAudioRenderer(null);
        settings.setTitle((bindless ? "Bindless" : "MDI") + " / " + app.count + " real model instances");
        app.setPauseOnLostFocus(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        if (withoutCount) {
            renderer.getCaps().remove(Caps.IndirectDrawCount);
        }
        // 第一步：加载原模型的 LOD 资产；共享网格而非复制几十万份顶点数据。
        String[] modelPaths = {"Models/Tank/LodTank.j3o", "Models/Boat/LodBoat.j3o", "Models/Buggy/LodBuggy.j3o"};
        String[] texturePaths = {"Textures/Terrain/BrickWall/BrickWall.jpg", "Textures/Terrain/Rock2/rock.jpg",
            "Textures/Terrain/Rocky/RockyTexture.jpg", "Textures/Terrain/Pond/Pond.jpg",
            "Textures/Terrain/splat/grass.jpg", "Textures/Terrain/splat/dirt.jpg",
            "Textures/Terrain/splat/road.jpg", "Textures/Terrain/PBR/Gravel015_1K_Color.png"};
        Texture[] textures = new Texture[32];
        for (int i = 0; i < textures.length; i++) {
            textures[i] = assetManager.loadTexture(texturePaths[i % texturePaths.length]).clone();
            textures[i].setWrap(i < 16 ? Texture.WrapMode.Repeat : Texture.WrapMode.MirroredRepeat);
            textures[i].setMagFilter(i % 2 == 0 ? Texture.MagFilter.Bilinear : Texture.MagFilter.Nearest);
        }
        Spatial[][] variants = new Spatial[3][32];
        float[] scales = new float[3];
        for (int type = 0; type < variants.length; type++) {
            Spatial source = assetManager.loadModel(modelPaths[type]);
            source.updateGeometricState();
            scales[type] = 2 / ((BoundingBox) source.getWorldBound()).getExtent(null).length();
            for (int variant = 0; variant < 32; variant++) {
                variants[type][variant] = source.clone(false);
                applyMaterials(variants[type][variant], variant, textures[variant]);
            }
        }
        // 第二步：创建 30 万模型根，旋转/非均匀缩放/平移不同，只有 1000 个移动。
        rootNode.attachChild(models);
        movers = new Spatial[Math.min(1000, count)];
        initialRotations = new Quaternion[movers.length];
        int columns = (int) Math.ceil(Math.sqrt(count));
        Random random = new Random(76319);
        for (int i = 0; i < count; i++) {
            int type = i % 3;
            Spatial model = variants[type][(i / 3) % 32].clone(false);
            float scale = scales[type];
            model.setLocalScale(scale * (.8f + random.nextFloat() * .4f),
                    scale * (.8f + random.nextFloat() * .4f), scale * (.8f + random.nextFloat() * .4f));
            model.rotate(0, random.nextFloat() * 6.283185f, 0);
            model.setLocalTranslation((i % columns - columns / 2) * 8, 0, -(i / columns) * 8);
            models.attachChild(model);
            if ((long) i * movers.length / count != (long) (i + 1) * movers.length / count) {
                int slot = (int) ((long) i * movers.length / count);
                movers[slot] = model;
                initialRotations[slot] = model.getLocalRotation().clone();
            }
        }
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal()));
        cam.setLocation(new Vector3f(0, 30, 45));
        cam.lookAt(new Vector3f(0, 0, -100), Vector3f.UNIT_Y);
        cam.setFrustumFar(4000);
        flyCam.setDragToRotate(true);
        flyCam.setMoveSpeed(100);
        // 第三步：选择原渲染路径，GPU Scene 包装它；两个功能独立，不互相隐式开启。
        pipeline = new GpuScenePipeline(assetManager, models, deferred
                ? new DeferredPipeline(assetManager, renderManager.getDeferredShadingModels()) : new ForwardPipeline());
        renderManager.setPipeline(viewPort, pipeline);
        pipeline.setEnabled(true);
        pipeline.setMaxDistance(600);
        pipeline.setMinPixels(2);
        pipeline.setLodThresholds(80, 30, 10, 3);
        applyFeature(!benchmark);
        times = new double[measureFrames];
        hud = new BitmapText(guiFont);
        hud.setLocalTranslation(10, cam.getHeight() - 10, 0);
        guiNode.attachChild(hud);
        if (benchmark) {
            hud.setCullHint(Spatial.CullHint.Always);
            setDisplayFps(false);
            setDisplayStatView(false);
            renderer.getStatistics().setEnabled(true);
        }
        inputManager.addMapping("Feature", new KeyTrigger(KeyInput.KEY_B));
        inputManager.addMapping("Motion", new KeyTrigger(KeyInput.KEY_M));
        inputManager.addMapping("GpuScene", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed || benchmark) {
                return;
            }
            if (name.equals("Feature")) {
                applyFeature(!feature);
            } else if (name.equals("Motion")) {
                motion = !motion;
            } else {
                pipeline.setEnabled(!pipeline.isEnabled());
            }
        }, "Feature", "Motion", "GpuScene");
    }

    private void applyMaterials(Spatial spatial, int variant, Texture texture) {
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            if (meshMerging && sharedMaterials[variant] != null) {
                geometry.setMaterial(materialMerging ? sharedMaterials[variant].clone() : sharedMaterials[variant]);
                return;
            }
            Material material;
            ColorRGBA color = new ColorRGBA(.6f + (variant % 4) * .1f, .8f, 1, 1);
            if (variant % 3 == 0) {
                material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
                material.setBoolean("UseMaterialColors", true);
                material.setColor("Diffuse", color);
                material.setTexture("DiffuseMap", texture);
            } else if (variant % 3 == 1) {
                material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
                material.setColor("BaseColor", color);
                material.setTexture("BaseColorMap", texture);
                material.setFloat("Roughness", .65f);
            } else {
                material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                material.setColor("Color", color);
                material.setTexture("ColorMap", texture);
            }
            sharedMaterials[variant] = material;
            geometry.setMaterial(material);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                applyMaterials(child, variant, texture);
            }
        }
    }

    private void applyFeature(boolean enabled) {
        feature = enabled;
        if (testBindless) {
            pipeline.setBindlessTextures(enabled);
        } else if (materialMerging) {
            pipeline.setMultiDrawIndirect(true);
            pipeline.setMeshMerging(true);
            pipeline.setMaterialMerging(enabled);
        } else if (compaction) {
            pipeline.setMultiDrawIndirect(true);
            pipeline.setMeshMerging(true);
            pipeline.setCommandCompaction(enabled);
        } else if (meshMerging) {
            pipeline.setMultiDrawIndirect(true);
            pipeline.setMeshMerging(enabled);
        } else {
            pipeline.setMultiDrawIndirect(enabled);
        }
    }

    private String featureName() {
        return testBindless ? "Bindless" : materialMerging ? "MaterialMerge" : compaction ? "Compaction" : meshMerging ? "CrossMesh" : "MDI";
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (benchmark) {
            // StatsAppState initializes after simpleInitApp and can disable counters.
            renderer.getStatistics().setEnabled(phaseFrame == 60 + measureFrames);
        }
        if (motion) {
            for (Spatial mover : movers) {
                mover.rotate(0, benchmark ? .005f : tpf * .5f, 0);
            }
        }
        if (!benchmark) {
            hud.setText(String.format(Locale.ROOT,
                    "%s B enabled=%s M motion=%s Space GPU=%s | models=%d geometries=%d batches=%d%n"
                    + "frame=%.2f ms | bindless support=%s resident=%d snapshot=%.1f MiB | 32 material variants/model",
                    featureName(), feature, motion, pipeline.isActive(), count,
                    pipeline.getInstanceCount(), pipeline.getBatchCount(), tpf * 1000,
                    renderer.getCaps().contains(Caps.BindlessTextures), renderer.getBindlessTextureCount(),
                    renderer.getBindlessTextureBytes() / 1048576.0));
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!benchmark) {
            return;
        }
        long now = System.nanoTime();
        phaseFrame++;
        if (phaseFrame > 60 && phaseFrame <= 60 + measureFrames) {
            times[phaseFrame - 61] = (now - lastFrame) / 1000000.0;
        }
        lastFrame = now;
        if (phaseFrame != 61 + measureFrames) {
            return;
        }
        if (!pipeline.isActive() || pipeline.getInstanceCount() < count) {
            throw new AssertionError("Incomplete GPU registration");
        }
        int submitted = pipeline.readSubmittedCommandCount();
        if (!feature) {
            referenceCommands = submitted;
        } else if (compaction && renderer.getCaps().contains(Caps.IndirectDrawCount)
                && submitted >= referenceCommands) {
            throw new AssertionError("Compaction did not remove empty commands");
        }
        System.out.println("COMMANDS submitted=" + submitted + " compaction=" + (compaction && feature));
        long visible = pipeline.readVisibleCount();
        long triangles = pipeline.readVisibleTriangleCount();
        if (testBindless && feature && renderer.getCaps().contains(Caps.BindlessTextures)
                && renderer.getBindlessTextureCount() == 0) {
            throw new AssertionError("No real bindless sampling");
        }
        ByteBuffer image = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBufferWithFormat(null, image, Image.Format.RGBA8);
        if (!feature) {
            reference = image;
            referenceVisible = visible;
            referenceTriangles = triangles;
        } else {
            if (visible != referenceVisible || triangles != referenceTriangles) {
                throw new AssertionError("Feature changed visible workload");
            }
            int changed = 0;
            for (int i = 0; i < image.capacity(); i += 4) {
                for (int channel = 0; channel < 3; channel++) {
                    if (Math.abs((image.get(i + channel) & 255) - (reference.get(i + channel) & 255)) > 12) {
                        changed++;
                        break;
                    }
                }
            }
            if (changed > cam.getWidth() * cam.getHeight() / 100) {
                throw new AssertionError("Feature image mismatch: " + changed);
            }
            System.out.println("FEATURE_IMAGE_PASS changedPixels=" + changed);
        }
        double[] sorted = times.clone();
        Arrays.sort(sorted);
        int[] statistics = new int[renderer.getStatistics().getLabels().length];
        renderer.getStatistics().getData(statistics);
        if (!feature) {
            referenceDraws = statistics[3];
        } else if (!testBindless && !compaction && statistics[3] >= referenceDraws) {
            throw new AssertionError("MDI did not reduce actual draw submissions");
        }
        System.out.printf(Locale.ROOT,
                "FEATURE_BENCH feature=%s enabled=%s motion=%s models=%d geometries=%d batches=%d draws=%d "
                + "visible=%d triangles=%d mean=%.3f p50=%.3f p95=%.3f ms resident=%d MiB=%.1f%n",
                featureName(), feature, motion, count, pipeline.getInstanceCount(),
                pipeline.getBatchCount(), statistics[3], visible, triangles,
                Arrays.stream(sorted).average().getAsDouble(), sorted[measureFrames / 2], sorted[(int) (measureFrames * .95)],
                renderer.getBindlessTextureCount(), renderer.getBindlessTextureBytes() / 1048576.0);
        phase++;
        if (phase == 4) {
            System.out.println("FEATURE_300K_PASS " + featureName() + " models=" + count);
            stop();
            return;
        }
        // 第四步：每次切换后预热 60 帧，避免把重建、编译、上传计入稳定帧时间。
        motion = phase >= 2;
        for (int i = 0; i < movers.length; i++) {
            movers[i].setLocalRotation(initialRotations[i]);
        }
        applyFeature(phase % 2 == 1);
        phaseFrame = 0;
        lastFrame = System.nanoTime();
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

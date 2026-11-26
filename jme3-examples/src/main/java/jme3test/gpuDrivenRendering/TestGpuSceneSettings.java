package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Automatic startup/runtime policy regression with real Tank/Boat/Buggy models.
 * Entry: TestGpuSceneSettings.main. Run gradlew :jme3-examples:gpuSettingsCheck.
 * Run gpuSettingsLwjgl3Check to verify the separate LWJGL 3 backend.
 * Requires OpenGL 4.3 for the accelerated checks; also simulates its absence.
 * No explicit GPU pipeline installation. Automatically verifies first-frame
 * GPU on/off restoration, Bindless off, material edits, Forward/Deferred selection,
 * and unsupported-device fallback by comparing rendered images. Exits on success.
 * Also verifies saved scene LOD thresholds, automatic defaults and restoration
 * on unloading through GPU triangle counts. The final orthographic fixture
 * verifies GPU frustum culling for a thin world AABB and a spherical bound,
 * including updates after both instances move. No keyboard input is required.
 * <pre>{@code
 * // 第一步：默认请求 GPU Scene 和 Bindless；旧项目可在启动前关闭。
 * AppSettings settings = new AppSettings(true);
 * settings.setGpuSceneEnabled(false);
 * app.setSettings(settings);
 * // 第二步：simpleInitApp/simpleUpdate 内可运行时开启，普通材质照常使用。
 * renderManager.setGpuSceneEnabled(true);
 * // 第三步：只关闭 Bindless，仍使用 GPU 剔除/实例化/MDI。
 * renderManager.setGpuSceneBindlessEnabled(false);
 * // 第四步：关闭 GPU Scene 当帧恢复普通队列，源模型/材质不变。
 * renderManager.setGpuSceneEnabled(false);
 * // 第五步：重新开启并选择 Deferred，能力不足时仍保留传统回退。
 * renderManager.setGpuSceneEnabled(true);
 * renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
 * // 第六步：SceneComposer 保存 GPU Scene LOD 后，游戏加载并挂载场景即可生效。
 * rootNode.attachChild(assetManager.loadModel("Scenes/Level.j3o"));
 * // 第七步：添加细长盒子并移动到视锥内外，GPU Scene 会读取更新后的世界包围盒。
 * Geometry thin = new Geometry("Thin bound", new Box(.25f, 8, .25f));
 * thin.setMaterial(material);
 * thin.setLocalTranslation(3, 0, -4);
 * rootNode.attachChild(thin);
 * // 在后续 simpleUpdate 中平移，保持引擎默认的球体和 AABB 视锥测试。
 * thin.setLocalTranslation(0, 0, -4);
 * }</pre>
 */
public final class TestGpuSceneSettings extends SimpleApplication {
    private int frame;
    private ByteBuffer reference;
    private Material material;
    private com.jme3.scene.Node lodSettings;
    private long fullTriangles;
    private long defaultTriangles;
    private long reducedTriangles;
    private Geometry thinBox;
    private Geometry sphereBoundBox;

    public static void main(String[] args) {
        TestGpuSceneSettings app = new TestGpuSceneSettings();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(.7f, .4f, .2f, 1));
        String[] paths = {"Models/Tank/LodTank.j3o", "Models/Boat/LodBoat.j3o", "Models/Buggy/LodBuggy.j3o"};
        for (int type = 0; type < paths.length; type++) {
            Spatial model = assetManager.loadModel(paths[type]);
            model.updateGeometricState();
            float radius = ((BoundingBox) model.getWorldBound()).getExtent(null).length();
            model.setLocalScale(2 / radius);
            model.setMaterial(material);
            for (int i = 0; i < 30; i++) {
                Spatial copy = model.clone(false);
                copy.setLocalTranslation((i % 6) * 5 - 12, type * 4, -(i / 6) * 5);
                rootNode.attachChild(copy);
            }
        }
        cam.setLocation(new Vector3f(0, 15, 35));
        cam.lookAt(new Vector3f(0, 3, -10), Vector3f.UNIT_Y);
        // Keep the image comparisons at full detail before exercising LOD.
        lodSettings = reloadLodSettings(0.001f);
        rootNode.attachChild(lodSettings);
    }

    private com.jme3.scene.Node reloadLodSettings(float... pixels) {
        com.jme3.scene.Node scene = new com.jme3.scene.Node("Saved LOD settings");
        com.jme3.post.WorldSettings settings = new com.jme3.post.WorldSettings();
        settings.setDisplayOutput(com.jme3.post.WorldSettings.DisplayOutput.Application);
        settings.setGpuLodThresholds(pixels);
        scene.addControl(settings);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            com.jme3.export.binary.BinaryExporter.getInstance().save(scene, bytes);
            return (com.jme3.scene.Node) new com.jme3.export.binary.BinaryImporter().load(bytes.toByteArray());
        } catch (java.io.IOException error) {
            throw new AssertionError("Cannot reload scene LOD settings", error);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        if (frame == 4 || frame == 10) {
            renderManager.setGpuSceneEnabled(false);
        } else if (frame == 6) {
            renderManager.setGpuSceneBindlessEnabled(false);
            renderManager.setGpuSceneEnabled(true);
        } else if (frame == 8) {
            material.setColor("Color", new ColorRGBA(.2f, .7f, .4f, 1));
        } else if (frame == 12) {
            renderManager.setGpuSceneEnabled(true);
            renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
        } else if (frame == 14) {
            renderManager.setRenderPath(RenderPath.Forward);
        } else if (frame == 16) {
            renderManager.setGpuSceneEnabled(false);
            renderer.getCaps().remove(Caps.OpenGL43);
            renderManager.setGpuSceneEnabled(true);
        } else if (frame == 18) {
            renderer.getCaps().add(Caps.OpenGL43);
            renderManager.setGpuSceneEnabled(false);
            renderManager.setGpuSceneEnabled(true);
        } else if (frame == 21 || frame == 25) {
            lodSettings.removeFromParent();
        } else if (frame == 23) {
            lodSettings = reloadLodSettings(100000, 50000, 10000, 1000);
            rootNode.attachChild(lodSettings);
        } else if (frame == 27) {
            setupFrustumFixture();
        } else if (frame == 28) {
            thinBox.setLocalTranslation(0, 0, -4);
        } else if (frame == 29) {
            sphereBoundBox.setLocalTranslation(3, 0, -8);
        } else if (frame == 30) {
            thinBox.setLocalTranslation(3, 0, -4);
        }
    }

    private void setupFrustumFixture() {
        rootNode.detachAllChildren();
        cam.setLocation(Vector3f.ZERO);
        cam.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        cam.setParallelProjection(true);
        cam.setFrustum(1, 30, -1, 1, 1, -1);

        // The AABB lies to the right of the orthographic view. Its enclosing
        // sphere reaches across both side planes because of its height.
        thinBox = new Geometry("Thin frustum fixture", new Box(.25f, 8, .25f));
        thinBox.setMaterial(material);
        thinBox.setLocalTranslation(3, 0, -4);
        rootNode.attachChild(thinBox);

        sphereBoundBox = new Geometry("Sphere bound frustum fixture", new Box(.25f, .25f, .25f));
        sphereBoundBox.setModelBound(new BoundingSphere(.5f, Vector3f.ZERO));
        sphereBoundBox.setMaterial(material);
        sphereBoundBox.setLocalTranslation(0, 0, -8);
        rootNode.attachChild(sphereBoundBox);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame == 3 || frame == 9) {
            reference = capture();
        }
        if (frame == 3 || frame == 6 || frame == 12 || frame == 18) {
            if (!(renderManager.getPipeline(viewPort) instanceof GpuScenePipeline)
                    || !((GpuScenePipeline) renderManager.getPipeline(viewPort)).isActive()) {
                throw new AssertionError("Missing automatic GPU Scene at " + frame);
            }
        }
        if (frame == 4 || frame == 6 || frame == 10 || frame == 12 || frame == 14 || frame == 16 || frame == 18) {
            compare(capture());
        }
        if (frame == 16 && renderManager.getPipeline(viewPort) != null) {
            throw new AssertionError("Unsupported hardware did not restore Forward");
        }
        if (frame == 20) {
            fullTriangles = visibleTriangles();
        } else if (frame == 22) {
            defaultTriangles = visibleTriangles();
        } else if (frame == 24) {
            reducedTriangles = visibleTriangles();
        } else if (frame == 26) {
            long restored = visibleTriangles();
            if (fullTriangles <= defaultTriangles || defaultTriangles <= reducedTriangles
                    || reducedTriangles <= 0 || restored != defaultTriangles) {
                throw new AssertionError("GPU Scene LOD triangles: " + fullTriangles + ", "
                        + defaultTriangles + ", " + reducedTriangles + ", " + restored);
            }
            System.out.println("GPU_SCENE_WORLD_LOD_PASS " + fullTriangles + " "
                    + defaultTriangles + " " + reducedTriangles + " " + restored);
        } else if (frame >= 27) {
            int expected = frame == 28 ? 2 : frame == 30 ? 0 : 1;
            GpuScenePipeline gpu = (GpuScenePipeline) renderManager.getPipeline(viewPort);
            int visible = gpu.readVisibleCount();
            if (visible != expected) {
                throw new AssertionError("GPU frustum instance count on frame " + frame
                        + ": " + visible + " expected " + expected);
            }
        }
        if (frame == 30) {
            System.out.println("GPU_SCENE_AABB_PASS outside/moved/inside/sphere");
            System.out.println("GPU_SETTINGS_PASS automatic/runtime/material/deferred/fallback");
            stop();
        }
    }

    private long visibleTriangles() {
        return ((GpuScenePipeline) renderManager.getPipeline(viewPort)).readVisibleTriangleCount();
    }

    private ByteBuffer capture() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBufferWithFormat(null, bytes, Image.Format.RGBA8);
        return bytes;
    }

    private void compare(ByteBuffer actual) {
        int changed = 0;
        for (int i = 0; i < actual.capacity(); i += 4) {
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs((actual.get(i + channel) & 255) - (reference.get(i + channel) & 255)) > 12) {
                    changed++;
                    break;
                }
            }
        }
        if (changed > cam.getWidth() * cam.getHeight() / 100) {
            throw new AssertionError("Runtime switch image mismatch at " + frame + ": " + changed);
        }
        System.out.println("GPU_SETTINGS_IMAGE frame=" + frame + " changed=" + changed);
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}

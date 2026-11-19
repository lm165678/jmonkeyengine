package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.*;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.scene.Node;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import java.io.*;
import java.util.Map;

/**
 * Scene-owned FPP round trip. Run TestWorldSettings for an automatically managed
 * FXAA viewport (floating-point color buffers required); standard fly-camera
 * controls apply. Run with --smoke for serialization/clone checks without OpenGL:
 * java -cp <engine-and-examples-classpath> jme3test.post.TestWorldSettings --smoke
 * Use --verify for a 40-frame native GL 4.3 attach/detach/re-attach check;
 * successful completion prints WORLD_SETTINGS_NATIVE_LIFECYCLE_PASS.
 * No manual viewport processor installation is required. Detached scenes release
 * their chain on the next update; manual FPPs take precedence.
 */
public class TestWorldSettings extends SimpleApplication {
    private boolean verify;
    private int verificationFrame;
    private Node world;
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--smoke")) {
            Node original = configuredWorld(null);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new BinaryExporter().save(original, bytes);
            Node loaded = (Node) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
            WorldSettings settings = loaded.getControl(WorldSettings.class);
            if (settings == null || settings.getPriority() != 7
                    || settings.getEditorOptions().get("FXAA") != 1d) {
                throw new AssertionError("WorldSettings round trip");
            }
            FilterPostProcessor a = settings.createPostProcessor(null);
            FilterPostProcessor b = settings.createPostProcessor(null);
            if (a == b || a.getFilter(FXAAFilter.class) == b.getFilter(FXAAFilter.class)) {
                throw new AssertionError("Shared filter history");
            }
            WorldSettings copy = loaded.clone(true).getControl(WorldSettings.class);
            copy.setPostProcessing(new FilterPostProcessor(null), Map.of());
            if (settings.createPostProcessor(null).getFilter(FXAAFilter.class) == null) {
                throw new AssertionError("Clone changed original");
            }
            com.jme3.renderer.ViewPort view = new com.jme3.renderer.ViewPort(
                    "World", new com.jme3.renderer.Camera(16, 16));
            var caps = java.util.EnumSet.of(com.jme3.renderer.Caps.HalfFloatColorBufferRGBA);
            WorldSettingsRuntime runtime = new WorldSettingsRuntime();
            runtime.sync(settings, null, view, caps);
            if (view.getProcessors().size() != 1) {
                throw new AssertionError("Automatic installation");
            }
            var installed = view.getProcessors().get(0);
            runtime.sync(settings, null, view, caps);
            if (view.getProcessors().get(0) != installed) {
                throw new AssertionError("Unchanged settings rebuilt");
            }
            settings.setEnabled(false);
            runtime.sync(settings, null, view, caps);
            if (!view.getProcessors().isEmpty()) {
                throw new AssertionError("Disabled world retained processor");
            }
            settings.setEnabled(true);
            FilterPostProcessor manual = new FilterPostProcessor(null);
            view.addProcessor(manual);
            runtime.sync(settings, null, view, caps);
            if (view.getProcessors().size() != 1 || view.getProcessors().get(0) != manual) {
                throw new AssertionError("Manual ownership was replaced");
            }
            view.removeProcessor(manual);
            runtime.sync(settings, null, view, caps);
            runtime.sync(null, null, view, caps);
            if (!view.getProcessors().isEmpty()) {
                throw new AssertionError("Detached world retained processor");
            }
            runtime.close();
            System.out.println("WORLD_SETTINGS_ROUND_TRIP_LIFECYCLE_PASS");
            return;
        }
        TestWorldSettings app = new TestWorldSettings();
        app.verify = args.length > 0 && args[0].equals("--verify");
        if (app.verify) {
            com.jme3.system.AppSettings settings = new com.jme3.system.AppSettings(true);
            settings.setResolution(320, 240);
            settings.setRenderer(com.jme3.system.AppSettings.LWJGL_OPENGL43);
            settings.setAudioRenderer(null);
            settings.setFrameRate(60);
            app.setSettings(settings);
            app.setShowSettings(false);
        }
        app.start();
    }

    private static Node configuredWorld(com.jme3.asset.AssetManager assets) throws IOException {
        // 第一步：把后处理配置保存到场景控制器；没有 GPU 资源写入 J3O。
        Node world = new Node("World");
        FilterPostProcessor chain = new FilterPostProcessor(assets);
        chain.addFilter(new FXAAFilter());
        WorldSettings settings = new WorldSettings();
        settings.setPriority(7);
        settings.setPostProcessing(chain, Map.of("FXAA", 1d));
        world.addControl(settings);
        return world;
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!verify) {
            return;
        }
        verificationFrame++;
        if (verificationFrame == 10 || verificationFrame == 30) {
            if (viewPort.getProcessors().size() != 1
                    || !viewPort.getProcessors().get(0).isInitialized()) {
                throw new AssertionError("FPP not initialized");
            }
            world.removeFromParent();
        } else if (verificationFrame == 20) {
            if (!viewPort.getProcessors().isEmpty()) {
                throw new AssertionError("Detached scene FPP still active");
            }
            rootNode.attachChild(world);
        } else if (verificationFrame == 40) {
            if (!viewPort.getProcessors().isEmpty()) {
                throw new AssertionError("FPP leaked after repeated detach");
            }
            System.out.println("WORLD_SETTINGS_NATIVE_LIFECYCLE_PASS");
            stop();
        }
    }

    @Override
    public void simpleInitApp() {
        try {
            world = configuredWorld(assetManager);
            Geometry box = new Geometry("Box", new Box(1, 1, 1));
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color", ColorRGBA.Orange);
            box.setMaterial(material);
            world.attachChild(box);
            // 第二步：挂载后场景服务自动应用；实际项目可换为 loadModel("Scenes/World.j3o")。
            rootNode.attachChild(world);
            cam.setLocation(new com.jme3.math.Vector3f(0, 0, 6));
            // 第三步：world.removeFromParent() 释放；控制器 setEnabled(false/true) 关闭/恢复。
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

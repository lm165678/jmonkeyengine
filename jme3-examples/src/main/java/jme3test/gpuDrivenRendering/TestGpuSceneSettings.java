package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
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
 * }</pre>
 */
public final class TestGpuSceneSettings extends SimpleApplication {
    private int frame;
    private ByteBuffer reference;
    private Material material;

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
        }
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
            System.out.println("GPU_SETTINGS_PASS automatic/runtime/material/deferred/fallback");
            stop();
        }
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

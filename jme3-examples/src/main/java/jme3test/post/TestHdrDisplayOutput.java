package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.ModelKey;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.WorldSettings;
import com.jme3.post.WorldSettings.DisplayOutput;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.lwjgl.LwjglWindow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Shows a linear shadow ramp (bottom) and highlights up to 8x SDR white (top).
 * Native output requires Windows HDR, standalone LWJGL3 desktop OpenGL and
 * floating-point color buffers with WGL_NV_DX_interop2 on the active adapter.
 * Press H to toggle the requested display output.
 * Run {@code gradlew :jme3-examples:hdrDisplayOutputSmoke} on an HDR-enabled display
 * to verify HDR, SDR, and restoration. Pass --verify-sdr to verify automatic
 * fallback on an SDR display, or --fullscreen to exercise fullscreen.
 * Pass --verify-world to verify HDR and SDR overrides from a serialized scene.
 *
 * <pre>{@code
 * // 第一步：Windows 开启 HDR，创建独立的 LWJGL3 窗口。
 * settings.setGammaCorrection(true);
 * settings.setHdrDisplayOutput(true);
 * app.setSettings(settings);
 * app.start();
 * // 第二步：保留高于 1.0 的线性高光，再呈现到显示器。
 * // 第三步：在渲染线程关闭输出开关，恢复原来的 SDR 路径。
 * app.enqueue(() -> app.getContext().getSettings().setHdrDisplayOutput(false));
 * }</pre>
 */
public class TestHdrDisplayOutput extends SimpleApplication {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 320;
    private boolean verify;
    private boolean verifySdr;
    private boolean verifyWorld;
    private Spatial loadedWorld;
    private int frame;

    public static void main(String[] args) {
        TestHdrDisplayOutput app = new TestHdrDisplayOutput();
        app.verify = Arrays.asList(args).contains("--verify");
        app.verifySdr = Arrays.asList(args).contains("--verify-sdr");
        app.verifyWorld = Arrays.asList(args).contains("--verify-world");
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Linear shadow and highlight ramps — H toggles HDR");
        settings.setResolution(WIDTH, HEIGHT);
        if (Arrays.asList(args).contains("--fullscreen")) {
            settings.setFullscreen(true);
        }
        settings.setAudioRenderer(null);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setGammaCorrection(true);
        settings.setHdrDisplayOutput(!app.verifyWorld);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        viewPort.setBackgroundColor(ColorRGBA.Black);
        for (int i = 0; i < 16; i++) {
            float fraction = i / 15f;
            addPatch(i, 32, fraction * fraction * 0.015f);
            addPatch(i, 176, fraction * 8f);
        }
        inputManager.addMapping("Toggle HDR", new KeyTrigger(KeyInput.KEY_H));
        ActionListener toggleOutput = (name, pressed, tpf) -> {
            if (pressed) {
                AppSettings activeSettings = context.getSettings();
                activeSettings.setHdrDisplayOutput(!activeSettings.isHdrDisplayOutput());
            }
        };
        inputManager.addListener(toggleOutput, "Toggle HDR");
    }

    private void addPatch(int column, int y, float linear) {
        Geometry patch = new Geometry("Linear gray " + linear, new Quad(40, 112));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(linear, linear, linear, 1f));
        patch.setMaterial(material);
        patch.setLocalTranslation(column * 40, y, 0);
        guiNode.attachChild(patch);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (verifyWorld) {
            verifyWorldOutput();
            return;
        }
        if (!verify && !verifySdr) {
            return;
        }
        boolean active = ((LwjglWindow) context).isHdrDisplayOutputActive();
        switch (++frame) {
            case 12:
                checkOutput(active, !verifySdr);
                context.getSettings().setHdrDisplayOutput(false);
                break;
            case 24:
                checkOutput(active, false);
                context.getSettings().setHdrDisplayOutput(true);
                break;
            case 36:
                checkOutput(active, !verifySdr);
                System.out.println(verifySdr ? "HDR_DISPLAY_SDR_FALLBACK_OK" : "HDR_DISPLAY_SWITCH_OK");
                stop();
                break;
            default:
                break;
        }
    }

    private void verifyWorldOutput() {
        boolean active = ((LwjglWindow) context).isHdrDisplayOutputActive();
        switch (++frame) {
            case 12:
                checkOutput(active, false);
                loadedWorld = loadWorld(DisplayOutput.Hdr);
                break;
            case 24:
                checkOutput(active, false);
                rootNode.attachChild(loadedWorld);
                break;
            case 36:
                checkOutput(active, true);
                loadedWorld.removeFromParent();
                break;
            case 48:
                checkOutput(active, false);
                context.getSettings().setHdrDisplayOutput(true);
                rootNode.attachChild(loadWorld(DisplayOutput.Sdr));
                break;
            case 60:
                checkOutput(active, false);
                rootNode.detachAllChildren();
                break;
            case 72:
                checkOutput(active, true);
                System.out.println("WORLD_DISPLAY_OUTPUT_J3O_OK");
                stop();
                break;
            default:
                break;
        }
    }

    private Spatial loadWorld(DisplayOutput mode) {
        Node world = new Node("World display output " + mode);
        WorldSettings settings = new WorldSettings();
        settings.setDisplayOutput(mode);
        world.addControl(settings);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            BinaryExporter.getInstance().save(world, bytes);
        } catch (IOException exception) {
            throw new AssertionError("Could not save world settings", exception);
        }
        return assetManager.loadAssetFromStream(new ModelKey("World.j3o"), new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void checkOutput(boolean active, boolean expected) {
        if (active != expected) {
            throw new AssertionError("Expected native HDR output " + expected + ", actual " + active);
        }
    }
}

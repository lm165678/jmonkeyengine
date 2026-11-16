package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.light.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Automated per-light shadow isolation test. Entry: main; no input controls.
 * Requires OpenGL 4.3 for GPU tiled/GpuScene coverage. Run
 * {@code gradlew :jme3-examples:managedShadowSmoke}.
 * A roof blocks a red sun while a non-shadowing green fill and blue ambient
 * remain visible. Checks Forward/Deferred/CPU tiled/GPU tiled, per-light settings,
 * the serialized light gate and global viewport disable/restore. Deferred phases
 * also require GBuffer callbacks, preventing silent Forward fallback with shadows. Tent PCF radii
 * 1 and 2 are exercised in the same run (hard comparison on the backlight). APIs:
 * RenderManager.getShadowManager(), Light.setCastShadow(), ViewPort.setRenderPath().
 * Managed casters use two-sided, zero polygon-offset rasterization; tune their
 * receiver depth/normal bias through LightShadowSettings on the render thread.
 * Material PreShadow polygon offsets remain effective for legacy processors.
 * No scene rebake or additional shadow samples are needed for this policy.
 * Also checks strength 1, 0.5 and 0 on four paths and both materials, preserving
 * other lights and ambient/emissive channels. Default 1 preserves old scenes.
 * See LightShadowSettings.setStrength for render-thread usage and restoration.
 * Soft managed shadows correct each PCF comparison using the receiver tangent
 * plane, analytically projected for directional, point and spot shadow maps.
 * Degenerate projections fall back to ordinary biased PCF. Hard shadows and
 * legacy filters retain their behavior. Receiver bias still handles depth
 * quantization and should not generally be set to zero.
 */
public final class TestManagedShadows extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private DirectionalLight sun;
    private Geometry receiver;
    private int frame;
    private int[] reference;
    private int observedGbuffers;
    private int[] strengthDark;
    private int[] strengthHalf;

    public static void main(String[] arguments) {
        TestManagedShadows app = new TestManagedShadows();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(160, 120);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        Thread timeout = new Thread(() -> {
            try {
                Thread.sleep(120000);
                System.exit(2);
            } catch (InterruptedException ignored) {
            }
        }, "managed-shadow-timeout");
        timeout.setDaemon(true);
        timeout.start();
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // 第一步：观察真实 GBuffer 生成，不能只检查最终画面相似。
        viewPort.setGBufferObserver((batch, target) -> observedGbuffers++);
        // 第一步：场景物体允许投射及接收阴影；光源默认请求投影。
        rootNode.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        sun = new DirectionalLight(new Vector3f(0, -1, 0), new ColorRGBA(1, 0, 0, 1));
        rootNode.addLight(sun);
        DirectionalLight fill = new DirectionalLight(new Vector3f(0, -1, 0), new ColorRGBA(0, 0.6f, 0, 1));
        fill.setCastShadow(false);
        rootNode.addLight(fill);
        DirectionalLight backlight = new DirectionalLight(new Vector3f(0, 1, 0), new ColorRGBA(0.1f, 0.1f, 0.1f, 1));
        rootNode.addLight(backlight);
        // A second shadowed light exercises independent resolution/cascade/filter settings.
        renderManager.getShadowManager(viewPort).getSettings(backlight).setMapSize(64);
        renderManager.getShadowManager(viewPort).getSettings(backlight).setCascadeCount(1);
        renderManager.getShadowManager(viewPort).getSettings(backlight).setFilterRadius(0);
        rootNode.addLight(new AmbientLight(new ColorRGBA(0, 0, 0.2f, 1)));
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Ambient", ColorRGBA.White);
        material.setColor("Specular", ColorRGBA.Black);
        Geometry floor = new Geometry("floor", new Box(4, 0.1f, 4));
        floor.setMaterial(material);
        floor.setLocalTranslation(0, -0.1f, 0);
        rootNode.attachChild(floor);
        receiver = floor;
        for (int i = 1; i <= 3; i++) {
            Geometry instance = floor.clone(false);
            instance.setLocalTranslation(i * 12, -0.1f, 0);
            rootNode.attachChild(instance);
        }
        Geometry roof = new Geometry("roof", new Box(1, 0.1f, 1));
        roof.setMaterial(material);
        roof.setLocalTranslation(0, 2, 0);
        rootNode.attachChild(roof);
        cam.setLocation(new Vector3f(0, 1, 5));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        cam.setFrustumPerspective(45, 160f / 120, 0.1f, 40);
        // 第二步：按灯配置阴影质量，同一个 light 始终返回同一个设置对象。
        renderManager.getShadowManager(viewPort).getSettings(sun).setMapSize(256);
        renderManager.getShadowManager(viewPort).getSettings(sun).setShadowDistance(30);
        // 第六步：管理器直接使用接收端偏移，不叠加材质隐藏的投射偏移。
        // 数值越大越容易产生接触分离；恢复默认时使用 0.001 和 0.02。
        renderManager.getShadowManager(viewPort).getSettings(sun).setDepthBias(0.001f);
        renderManager.getShadowManager(viewPort).getSettings(sun).setNormalBias(0.02f);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        observedGbuffers = 0;
        if (frame >= 152) {
            int strengthPhase = (frame - 152) / 8;
            int step = strengthPhase % 3;
            viewPort.setRenderPath(PATHS[(strengthPhase / 3) % 4]);
            renderManager.getShadowManager(viewPort).setEnabled(true);
            sun.setCastShadow(true);
            renderManager.getShadowManager(viewPort).getSettings(sun).setEnabled(true);
            renderManager.getShadowManager(viewPort).getSettings(sun).setStrength(1f - step * 0.5f);
            if (frame == 152 || frame == 248) {
                setStrengthMaterial(frame == 248);
            }
            return;
        }
        int phase = frame / 8 % 8;
        if (frame == 64) {
            // 第四步：同样验证 PBR，蓝色自发光不应被红色灯的阴影压暗。
            Material pbr = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            pbr.setColor("BaseColor", ColorRGBA.White);
            pbr.setFloat("Metallic", 0);
            pbr.setFloat("Roughness", 1);
            pbr.setColor("Emissive", new ColorRGBA(0, 0, 0.2f, 1));
            rootNode.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry) {
                    ((Geometry) spatial).setMaterial(pbr);
                }
            });
        }
        if (frame == 128) {
            cam.setParallelProjection(true);
            cam.setFrustum(0.1f, 40, -4, 4, 3, -3);
        }
        if (frame == 136) {
            receiver.setShadowMode(RenderQueue.ShadowMode.Cast);
        }
        if (frame == 144) {
            receiver.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        }
        viewPort.setRenderPath(PATHS[Math.min(phase, 3)]);
        // 第五步：对照默认和高质量连续 PCF；半径为 0 时保留硬阴影。
        renderManager.getShadowManager(viewPort).getSettings(sun).setFilterRadius(phase == 3 ? 2 : 1);
        // 第三步：分别切换视口配置、场景灯开关和整个视口，检查恢复行为。
        renderManager.getShadowManager(viewPort).getSettings(sun).setEnabled(phase != 4);
        sun.setCastShadow(phase != 5);
        renderManager.getShadowManager(viewPort).setEnabled(phase != 6);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 8 == 7) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(160 * 120 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            Vector3f projected = cam.getScreenCoordinates(Vector3f.ZERO);
            int pixel = ((int) projected.y * 160 + (int) projected.x) * 4;
            int[] color = {pixels.get(pixel) & 255, pixels.get(pixel + 1) & 255, pixels.get(pixel + 2) & 255};
            if (frame >= 152) {
                int strengthPhase = (frame - 152) / 8;
                int step = strengthPhase % 3;
                if (step == 0) {
                    strengthDark = color;
                } else if (step == 1) {
                    strengthHalf = color;
                } else {
                    if (strengthDark[0] > 12 || color[0] < 80
                            || Math.abs(strengthHalf[0] * 2 - strengthDark[0] - color[0]) > 6) {
                        throw new AssertionError("Shadow strength does not interpolate direct light");
                    }
                    for (int channel = 1; channel < 3; channel++) {
                        if (Math.abs(color[channel] - strengthDark[channel]) > 3
                                || Math.abs(strengthHalf[channel] - strengthDark[channel]) > 3) {
                            throw new AssertionError("Strength changed another light or indirect/emissive color");
                        }
                    }
                }
                if ((strengthPhase / 3) % 4 != 0 && observedGbuffers == 0) {
                    throw new AssertionError("Strength test silently fell back to Forward");
                }
                System.out.println("SHADOW_STRENGTH phase=" + strengthPhase
                        + " RGB=" + java.util.Arrays.toString(color));
                if (frame == 343) {
                    System.out.println("MANAGED SHADOW STRENGTH PASS");
                    stop();
                }
                frame++;
                return;
            }
            int phase = frame / 8 % 8;
            System.out.println("MANAGED SHADOW phase=" + phase + " RGB=" + java.util.Arrays.toString(color));
            if (phase == 0) {
                reference = color;
                com.jme3.renderer.pipeline.OpaquePipeline pipeline = manager.getPipeline(viewPort);
                if (!(pipeline instanceof com.jme3.renderer.gpuscene.GpuScenePipeline)
                        || !((com.jme3.renderer.gpuscene.GpuScenePipeline) pipeline).isActive()) {
                    throw new AssertionError("Managed shadows disabled GpuScene");
                }
            }
            if (phase > 0 && observedGbuffers == 0) {
                throw new AssertionError("Shadows caused deferred rendering to fall back: "
                        + manager.getRenderPipeline(viewPort).getFallbackReason());
            }
            boolean disabled = (phase >= 4 && phase <= 6) || (frame >= 136 && frame < 144);
            if ((!disabled && color[0] > 12) || (disabled && color[0] < 80)
                    || color[1] < 30 || color[2] < 10) {
                throw new AssertionError("Incorrect per-light shadow isolation");
            }
            if (Math.abs(color[1] - reference[1]) > 3 || Math.abs(color[2] - reference[2]) > 3) {
                throw new AssertionError("A shadow modified another light or ambient contribution");
            }
            if (frame == 151) {
                System.out.println("MANAGED SHADOW SMOKE PASS");
            }
        }
        frame++;
    }

    private void setStrengthMaterial(boolean pbr) {
        // 第一步：保留红色遮挡灯与绿色补光；蓝色来自环境项或自发光。
        Material material = new Material(assetManager, pbr
                ? "Common/MatDefs/Light/PBRLighting.j3md" : "Common/MatDefs/Light/Lighting.j3md");
        if (pbr) {
            material.setColor("BaseColor", ColorRGBA.White);
            material.setFloat("Metallic", 0);
            material.setFloat("Roughness", 1);
            material.setColor("Emissive", new ColorRGBA(0, 0, 0.2f, 1));
        } else {
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", ColorRGBA.White);
            material.setColor("Ambient", ColorRGBA.White);
            material.setColor("Specular", ColorRGBA.Black);
        }
        // 第二步：运行时依次设为 1、0.5、0；仅红色直射光应改变。
        // 第三步：恢复 1 即完整阴影，不修改材质或 GI 数据。
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                ((Geometry) spatial).setMaterial(material);
            }
        });
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}

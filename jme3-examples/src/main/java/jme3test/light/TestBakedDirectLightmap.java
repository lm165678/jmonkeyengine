package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.environment.gi.BakedDirectionalShadow;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Two-light direct Lightmap playback with moving casters and receivers.
 * Run {@code gradlew :jme3-examples:bakedDirectLightmapSmoke}. Requires
 * desktop OpenGL 4.3. No input controls. The example checks pixels on the
 * Forward and TiledDeferredGPU paths, using a reloaded scene and streamed map.
 * <pre>
 * 第一步：为固定几何配置 UV2 和 Cast/Receive，在渲染线程调用 capture 保存方向光深度。
 * 第二步：逐灯调用 bakeDirect，使用 withDirect 将直射层附加到原有间接 Lightmap。
 * 第三步：GiProbeVolume 保存共享快照，ProbeLightmapControl 引用独立贴图，保存场景。
 * 第四步：运行时移动 Dynamic 投影者和接收者，观察两套阴影同时生效。
 * 第五步：清除共享快照可恢复该光源原有实时路径，无需修改节点 Cast/Receive。
 * </pre>
 */
public final class TestBakedDirectLightmap extends SimpleApplication {
    private static final int SIZE = 96;
    private Geometry floor;
    private Geometry movingCaster;
    private Geometry movingReceiver;
    private Geometry fixedCaster;
    private Node loaded;
    private DirectionalLight redLight;
    private DirectionalLight blueLight;
    private FrameBuffer target;
    private Path directory;
    private ByteBuffer readback;
    private int waited;
    private int stage;
    private float firstShadow;
    private float bakedAfterDisable;

    public static void main(String[] args) {
        var app = new TestBakedDirectLightmap();
        var config = new AppSettings(true);
        config.setRenderer(AppSettings.LWJGL_OPENGL43);
        config.setGpuSceneEnabled(true);
        config.setResolution(SIZE, SIZE);
        config.setAudioRenderer(null);
        app.setSettings(config);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        cam.setParallelProjection(true);
        cam.setFrustum(0.1f, 30f, -3f, 3f, 3f, -3f);
        cam.setLocation(new Vector3f(0f, 0f, 10f));
        cam.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        target = new FrameBuffer(SIZE, SIZE, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        viewPort.setOutputFrameBuffer(target);
        readback = BufferUtils.createByteBuffer(SIZE * SIZE * 16);

        Node scene = new Node("Baked shadow fixture");
        Geometry originalFloor = geometry("Floor", new Quad(6, 6), -3f, -3f, 0f, ShadowMode.Receive, Mobility.Static);
        originalFloor.getMesh().setBuffer(Type.TexCoord2, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        com.jme3.util.TangentBinormalGenerator.generate(originalFloor.getMesh());
        scene.attachChild(originalFloor);
        scene.attachChild(geometry("Fixed", new Box(.3f, .45f, .15f), -2, 0, 2, ShadowMode.Cast, Mobility.Static));
        scene.attachChild(geometry("Moving caster", new Box(.3f, .45f, .15f), .5f, 0, 2,
                ShadowMode.Cast, Mobility.Dynamic));
        scene.attachChild(geometry("Moving receiver", new Quad(.4f, .5f), -1.2f, -.25f, .05f,
                ShadowMode.Receive, Mobility.Dynamic));
        Vector3f direction = new Vector3f(.5f, 0f, -1f).normalizeLocal();
        DirectionalLight red = new DirectionalLight(direction, new ColorRGBA(1f, 0f, 0f, 1f));
        DirectionalLight blue = new DirectionalLight(direction, new ColorRGBA(0f, 0f, 1f, 1f));
        scene.addLight(red);
        scene.addLight(blue);
        GiProbeVolume volume = new GiProbeVolume();
        scene.attachChild(volume);
        scene.updateGeometricState();

        BakedDirectionalShadow a = BakedDirectionalShadow.capture(renderManager, scene, red, 128);
        BakedDirectionalShadow b = BakedDirectionalShadow.capture(renderManager, scene, blue, 128);
        Texture2D redDirect = ProbeLightmapBaker.bakeDirect(renderManager, assetManager, originalFloor, a, 64, 64);
        Texture2D blueDirect = ProbeLightmapBaker.bakeDirect(renderManager, assetManager, originalFloor, b, 64, 64);
        ByteBuffer black = BufferUtils.createByteBuffer(64 * 64 * 8);
        Texture2D indirect = new Texture2D(new Image(Image.Format.RGBA16F, 64, 64, black, ColorSpace.Linear));
        ProbeLightmapAsset map = ProbeLightmapAsset.withDirect(indirect, false, List.of(redDirect, blueDirect),
                List.of(a, b));
        volume.setBakedDirectionalShadows(new BakedDirectionalShadow[]{a, b});
        try {
            directory = Files.createTempDirectory("jme-direct-lightmap-");
            new BinaryExporter().save(map, directory.resolve("direct.j3o").toFile());
            originalFloor.addControl(new ProbeLightmapControl("direct.j3o", originalFloor.getWorldTransform(), 100));
            new BinaryExporter().save(scene, directory.resolve("scene.j3o").toFile());
            assetManager.registerLocator(directory.toString(), FileLocator.class);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        loaded = (Node) assetManager.loadModel("scene.j3o");
        redLight = (DirectionalLight) loaded.getLocalLightList().get(0);
        blueLight = (DirectionalLight) loaded.getLocalLightList().get(1);
        floor = (Geometry) loaded.getChild("Floor");
        movingCaster = (Geometry) loaded.getChild("Moving caster");
        movingReceiver = (Geometry) loaded.getChild("Moving receiver");
        fixedCaster = (Geometry) loaded.getChild("Fixed");
        if (floor.getShadowMode() != ShadowMode.Receive || movingCaster.getShadowMode() != ShadowMode.Cast
                || loaded.getChild("Fixed").getShadowMode() != ShadowMode.Cast) {
            throw new AssertionError("The authored shadow modes did not survive saving");
        }
        rootNode.attachChild(loaded);
        viewPort.setRenderPath(RenderPath.Forward);
    }

    private Geometry geometry(String name, com.jme3.scene.Mesh mesh, float x, float y, float z,
            ShadowMode mode, Mobility mobility) {
        Geometry result = new Geometry(name, mesh);
        Material material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        material.setFloat("Metallic", 0f);
        material.setFloat("Roughness", 1f);
        result.setMaterial(material);
        result.setShadowMode(mode);
        result.setMobility(mobility);
        result.setLocalTranslation(x, y, z);
        return result;
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (floor.getResidentDirectLightmap() == null) {
            if (++waited > 600) {
                throw new AssertionError("Direct Lightmap did not stream");
            }
            return;
        }
        renderer.readFrameBufferWithFormat(target, readback, Image.Format.RGBA32F);
        var resident = floor.getResidentDirectLightmap();
        var shadows = renderManager.getShadowManager(viewPort);
        if (stage == 4 || stage == 5) {
            if (shadows.hasBakedShadowRecords() || !shadows.hasBakedDirectLighting()
                    || pixel(1.5f, 0) < .1f || pixel(1.5f, 2) < .1f
                    || pixel(-1f, 0) > .04f || pixel(-1f, 1.6f, 0) > .04f) {
                throw new AssertionError("Disabled/detached lights lost their saved diffuse or baked static shadow");
            }
            if (stage == 4) {
                bakedAfterDisable = pixel(1.5f, 0);
                enqueue(() -> {
                    loaded.removeLight(redLight);
                    loaded.removeLight(blueLight);
                });
            } else {
                if (Math.abs(pixel(1.5f, 0) - bakedAfterDisable) > .001f) {
                    throw new AssertionError("Removing disabled lights changed baked shading");
                }
                enqueue(this::applyDetailNormal);
            }
            stage++;
            return;
        }
        if (stage == 6) {
            if (Math.abs(pixel(.9f, 0) - pixel(2.25f, 0)) < .08f) {
                throw new AssertionError("Directional Lightmap failed to evaluate the live normal texture");
            }
            enqueue(() -> {
                floor.getMaterial().clearParam("NormalMap");
                floor.getMaterial().setFloat("Metallic", 1f);
                floor.getMaterial().setFloat("Roughness", .5f);
                redLight.setEnabled(true);
                blueLight.setEnabled(true);
                redLight.setCastShadow(true);
                loaded.addLight(redLight);
                loaded.addLight(blueLight);
            });
            stage++;
            return;
        }
        if (stage == 7) {
            if (!shadows.hasBakedDirectLighting() || pixel(.9f, 0) < .04f || pixel(.9f, 2) < .04f) {
                throw new AssertionError("The direct Lightmap suppressed the live metallic highlights");
            }
            enqueue(() -> {
                redLight.setEnabled(false);
                blueLight.setEnabled(false);
            });
            stage++;
            return;
        }
        if (stage == 8) {
            if (!shadows.hasBakedDirectLighting() || pixel(.9f, 0) > .005f || pixel(.9f, 2) > .005f) {
                throw new AssertionError("Disabled lights still contributed metallic highlights");
            }
            enqueue(() -> {
                floor.getMaterial().setFloat("Metallic", 0f);
                floor.getMaterial().setFloat("Roughness", 1f);
                redLight.setEnabled(true);
                blueLight.setEnabled(true);
                fixedCaster.move(0, 1.6f, 0);
            });
            stage++;
            return;
        }
        if (stage == 9) {
            if (shadows.getBakedLightIndex(resident.getDirectShadowId(0)) >= 0
                    || shadows.getBakedLightIndex(resident.getDirectShadowId(1)) >= 0
                    || shadows.hasBakedDirectLighting()
                    || pixel(-1, 0) <= .1f || fixedCaster.getShadowMode() != ShadowMode.Cast
                    || floor.getShadowMode() != ShadowMode.Receive) {
                throw new AssertionError("A changed static caster did not restore realtime lighting and shadows");
            }
            System.out.println("BAKED_DIRECT_LIGHTMAP_PASS");
            stop();
            return;
        }
        if (resident.getDirectBandCount() != 2
                || shadows.getBakedLightIndex(resident.getDirectShadowId(0)) < 0
                || shadows.getBakedLightIndex(resident.getDirectShadowId(1)) < 0
                || shadows.getBakedLightIndex(resident.getDirectShadowId(0))
                        == shadows.getBakedLightIndex(resident.getDirectShadowId(1))) {
            throw new AssertionError("The two streamed direct bands did not resolve distinct baked lights");
        }
        float dynamicInStatic = pixel(-1, 0);
        float bakedUnderDynamic = pixel(1.5f, 0);
        float exposed = pixel(.9f, 0);
        if (stage == 0) {
            firstShadow = bakedUnderDynamic;
            if (exposed < .1f || dynamicInStatic >= exposed * .45f || bakedUnderDynamic >= exposed * .45f
                    || pixel(.9f, 2) < .1f || pixel(1.5f, 2) >= pixel(.9f, 2) * .45f) {
                throw new AssertionError("Expected shadows from both sources: " + exposed + ", "
                        + dynamicInStatic + ", " + bakedUnderDynamic);
            }
            enqueue(() -> {
                movingCaster.move(0, 1.6f, 0);
                movingReceiver.move(0, 1.6f, 0);
                viewPort.setRenderPath(RenderPath.TiledDeferredGPU);
            });
            stage++;
        } else if (stage++ == 3) {
            if (bakedUnderDynamic <= firstShadow + .07f || pixel(-1, 0) >= exposed * .45f
                    || pixel(-1, 1.6f, 0) <= .1f) {
                throw new AssertionError("Moving one caster changed the static shadow or left a stale dynamic shadow");
            }
            enqueue(() -> {
                redLight.setEnabled(false);
                redLight.setCastShadow(false);
                blueLight.setEnabled(false);
            });
        }
    }

    private void applyDetailNormal() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(16);
        for (int x = 0; x < 4; x++) {
            pixels.put((byte) ((x & 1) == 0 ? 22 : 233));
            pixels.put((byte) 128);
            pixels.put((byte) 198);
            pixels.put((byte) 255);
        }
        pixels.flip();
        Texture2D normal = new Texture2D(new Image(Image.Format.RGBA8, 4, 1, pixels, ColorSpace.Linear));
        normal.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        normal.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        floor.getMaterial().setTexture("NormalMap", normal);
    }

    private float pixel(float x, int channel) {
        return pixel(x, 0, channel);
    }

    private float pixel(float x, float y, int channel) {
        int px = Math.round((x + 3f) * SIZE / 6f - 0.5f);
        int py = Math.round((y + 3f) * SIZE / 6f - 0.5f);
        return readback.getFloat((py * SIZE + px) * 16 + channel * Float.BYTES);
    }

    @Override
    public void destroy() {
        super.destroy();
        if (readback != null) {
            BufferUtils.destroyDirectBuffer(readback);
        }
        if (directory != null) {
            try {
                Files.deleteIfExists(directory.resolve("direct.j3o"));
                Files.deleteIfExists(directory.resolve("scene.j3o"));
                Files.deleteIfExists(directory);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        System.err.println(message);
        error.printStackTrace();
        System.exit(1);
    }
}

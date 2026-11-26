package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Directional indirect Lightmap playback with an analytic texture fixture.
 * Run {@code gradlew :jme3-examples:directionalProbeLightmapSmoke}. Requires
 * desktop OpenGL 4.3. No input controls. The example tests the serialized
 * asset references through scene services and checks off/on/off pixel values
 * in Forward, Deferred and both Tiled Deferred paths.
 * <pre>
 * 第一步：为静态 Geometry 准备 UV2 和烘焙的探针场。
 * 第二步：在渲染线程用 ProbeLightmapBaker.bakeField 保存原 Lightmap，
 *        再用 bakeDirectionalField 保存同尺寸的独立方向纹理 J3O。
 * 第三步：用 ProbeLightmapControl 的第四个参数加入方向纹理路径。
 * 第四步：在渲染线程调用 world.setDirectionalLightmapEnabled(true)，
 *        等方向纹理驻留后就会使用方向光照。设回 false 恢复原有结果。
 * </pre>
 */
public final class TestDirectionalProbeLightmap extends SimpleApplication {
    private static final int SIZE = 64;
    private static final RenderPath[] PATHS = {
        RenderPath.Forward, RenderPath.Deferred, RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU
    };
    private WorldSettings world;
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 16);
    private Geometry receiver;
    private FrameBuffer target;
    private int path;
    private int step;
    private int waited;
    private float ordinary;

    public static void main(String[] args) {
        var app = new TestDirectionalProbeLightmap();
        var settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setResolution(SIZE, SIZE);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        cam.setLocation(new Vector3f(0.5f, 0.5f, 2f));
        cam.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        target = new FrameBuffer(SIZE, SIZE, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA32F));
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        viewPort.setOutputFrameBuffer(target);
        viewPort.setRenderPath(PATHS[path]);
        var mesh = new Quad(1f, 1f);
        mesh.setBuffer(Type.TexCoord2, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        var source = new Geometry("Directional indirect receiver", mesh);
        source.setMaterial(new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md"));
        source.getMaterial().setFloat("Metallic", 0f);
        var fixture = new Node("Directional Lightmap fixture");
        fixture.attachChild(source);
        fixture.addControl(new WorldSettings());
        fixture.updateGeometricState();
        try {
            Path directory = Files.createTempDirectory("directional-probe-lightmap");
            directory.toFile().deleteOnExit();
            assetManager.registerLocator(directory.toString(), FileLocator.class);
            Path base = directory.resolve("base.j3o");
            Path direction = directory.resolve("direction.j3o");
            BinaryExporter exporter = new BinaryExporter();
            exporter.save(new ProbeLightmapAsset(constantMap(2f, 0, 0, 1f), false), base.toFile());
            exporter.save(constantMap(0, 0, 0.5f, 0), direction.toFile());
            base.toFile().deleteOnExit();
            direction.toFile().deleteOnExit();
            source.addControl(new ProbeLightmapControl("base.j3o", source.getWorldTransform(), 10f,
                    "direction.j3o"));
            var encoded = new ByteArrayOutputStream();
            exporter.save(fixture, encoded);
            var importer = new BinaryImporter();
            importer.setAssetManager(assetManager);
            var reloaded = (Node) importer.load(new ByteArrayInputStream(encoded.toByteArray()));
            receiver = (Geometry) reloaded.getChild(0);
            world = reloaded.getControl(WorldSettings.class);
            if (world.isDirectionalLightmapEnabled()) {
                throw new AssertionError("Direction playback should default to off");
            }
            rootNode.attachChild(reloaded);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Texture2D constantMap(float r, float g, float b, float a) {
        ByteBuffer data = BufferUtils.createByteBuffer(32);
        for (int texel = 0; texel < 4; texel++) {
            data.putShort(FastMath.convertFloatToHalf(r));
            data.putShort(FastMath.convertFloatToHalf(g));
            data.putShort(FastMath.convertFloatToHalf(b));
            data.putShort(FastMath.convertFloatToHalf(a));
        }
        data.flip();
        return new Texture2D(new Image(Image.Format.RGBA16F, 2, 2, data, ColorSpace.Linear));
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (++waited > 1800) {
            throw new AssertionError("Timed out at " + PATHS[path] + " direction step " + step);
        }
        boolean directionResident = receiver.getResidentDirectionalLightmap() != null;
        if (receiver.getResidentProbeLightmap() == null || directionResident != (step == 1)) {
            return;
        }
        pixels.clear();
        renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA32F);
        float red = pixels.getFloat((SIZE * SIZE / 2 + SIZE / 2) * 16);
        if (step == 0) {
            ordinary = red;
            if (ordinary < 0.1f) {
                throw new AssertionError("Missing original indirect light");
            }
            world.setDirectionalLightmapEnabled(true);
            step = 1;
        } else if (step == 1) {
            if (Math.abs(red - ordinary * 1.5f) > 0.01f) {
                throw new AssertionError("Direction response on " + PATHS[path] + ": " + ordinary + " -> " + red);
            }
            world.setDirectionalLightmapEnabled(false);
            step = 2;
        } else {
            if (Float.floatToIntBits(red) != Float.floatToIntBits(ordinary)) {
                throw new AssertionError("RGB playback did not restore its previous value on " + PATHS[path]);
            }
            System.out.println("DIRECTIONAL_PROBE_LIGHTMAP_PASS " + PATHS[path]);
            if (++path == PATHS.length) {
                stop();
                return;
            }
            viewPort.setRenderPath(PATHS[path]);
            step = 0;
        }
        waited = 0;
    }

    @Override
    public void handleError(String message, Throwable error) {
        System.err.println(message);
        if (error != null) {
            error.printStackTrace();
        }
        System.exit(1);
    }
}

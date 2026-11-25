package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.bounding.BoundingBox;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.MatParam;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.material.skin.SkinNormalFilter;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Runnable pre-integrated skin example. GLSL330/ES300 and half-float texture
 * filtering are required; this desktop test requests OpenGL4.3 for all paths.
 * Run gradlew :jme3-examples:preintegratedSkinDemo. F5 toggles scattering;
 * F6 toggles diffuse-normal smoothing. The fly camera uses drag and WASD.
 *
 * <p>For the supplied external head, pass
 * -PskinAssets=E:/MyWorks/JmonkeyProjects/PrecomputeSSSTesting/assets.
 * Default keys are Models/LeePerrySmith.j3o, Models/Map-COL.jpg and
 * Models/Infinite-Level_02_Tangent_SmoothUV.jpg. Override with skinModel,
 * skinAlbedo and skinNormal Gradle properties. The head is centered and scaled
 * to 24cm tall in memory; external files are never written or distributed.
 * Normals default to OpenGL; -PskinNormalType=-1 selects DirectX.
 * To inspect an SDK-exported material, also pass
 * -PskinMaterialAssets=path/to/export/assets and "-PskinMaterial=Skin/Head.j3m".
 *
 * <p>Automated verification: gradlew :jme3-examples:preintegratedSkinSmoke.
 * Optional external-head properties also apply. Checks regular PBR against
 * strength zero, missing LUT, black mask and zero curvature; exercises explicit
 * curvature/diffuse normals, minimum-LOD normal sampling, four render selectors
 * and GPUScene on/off, followed by generated RGB normals, DirectX normals and
 * partial/per-channel overrides. Skin uses forward fallback in deferred views. Existing
 * Lightmap files/shaders are not changed. Screenshots are under the engine's
 * build/skin-v2/images directory. The SDK has the interactive LUT exporter at
 * Tools / Textures / Pre-integrated Skin Tools; no SDK build is run by this task.
 *
 * <p>Key APIs: SkinLutGenerator.generate/Result.applyTo, SkinStrength,
 * SkinWorldToMillimeters, SkinNormalMinLod and SkinDiffuseNormalMap.
 * Defaults are meter units, strength1 and normal minimum LOD3. The LUT remains
 * linear; only inspection PNGs are display-encoded. No colored shadow-penumbra
 * scattering or SSSS is implemented here. Optional thickness transmission has
 * its own runnable TestSkinTransmission example and is disabled by default.
 */
public final class TestPreintegratedSkin extends SimpleApplication {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private final List<Geometry> subjects = new ArrayList<>();
    private Material authored;
    private boolean verify;
    private boolean scattering = true;
    private boolean normals = true;
    private int frame;
    private byte[] pbrReference;
    private byte[] pathReference;
    private Texture2D detail;
    private Texture2D[] filtered;
    private final java.util.Map<String, Texture> authoredNormals = new java.util.LinkedHashMap<>();
    private Vector3f authoredNormalWeights;

    public static void main(String[] args) {
        TestPreintegratedSkin app = new TestPreintegratedSkin();
        app.verify = args.length == 1 && "--verify".equals(args[0]);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setSamples(0);
        settings.setGammaCorrection(true);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start(app.verify ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setDragToRotate(true);
        flyCam.setMoveSpeed(.2f);
        // 第一步：加载含默认线性 LUT 的独立皮肤材质。
        String materialAssets = System.getProperty("skin.materialAssets");
        if (materialAssets != null) {
            assetManager.registerLocator(materialAssets, FileLocator.class);
        }
        authored = assetManager.loadMaterial(System.getProperty("skin.material", "Common/Materials/PreintegratedSkin.j3m"));
        Spatial model;
        String assets = System.getProperty("skin.assets");
        if (assets == null) {
            model = new Geometry("Skin sphere", new Sphere(64, 96, .12f));
        } else {
            // 第二步：只读加载原始工程；在示例中临时归一化真实尺寸。
            assetManager.registerLocator(assets, FileLocator.class);
            model = assetManager.loadModel(System.getProperty("skin.model", "Models/LeePerrySmith.j3o"));
            bind("BaseColorMap", System.getProperty("skin.albedo", "Models/Map-COL.jpg"));
            bind("NormalMap", System.getProperty("skin.normal", "Models/Infinite-Level_02_Tangent_SmoothUV.jpg"));
            authored.setColor("BaseColor", ColorRGBA.White);
            model.updateGeometricState();
            BoundingBox bound = (BoundingBox) model.getWorldBound();
            float scale = .12f / bound.getYExtent();
            Node holder = new Node("24cm head");
            holder.attachChild(model);
            holder.setLocalScale(scale);
            holder.setLocalTranslation(bound.getCenter().mult(-scale));
            model = holder;
        }
        authored.setFloat("NormalType", Float.parseFloat(System.getProperty("skin.normalType", "1")));
        for (String parameter : new String[]{"SkinDiffuseNormalMap", "SkinRedNormalMap", "SkinGreenNormalMap", "SkinBlueNormalMap"}) {
            if (authored.getTextureParam(parameter) != null) {
                authoredNormals.put(parameter, authored.getTextureParam(parameter).getTextureValue());
            }
        }
        authoredNormalWeights = ((Vector3f) authored.getParamValue("SkinNormalWeights")).clone();
        MikktspaceTangentGenerator.generate(model);
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                Geometry geometry = (Geometry) spatial;
                geometry.setMaterial(authored);
                subjects.add(geometry);
            }
        });
        rootNode.attachChild(model);
        DirectionalLight sun = new DirectionalLight(new Vector3f(-1f, -.1f, -.35f).normalizeLocal(), ColorRGBA.White);
        sun.setIntensity(3f);
        sun.setCastShadow(false);
        rootNode.addLight(sun);
        rootNode.addLight(new AmbientLight(new ColorRGBA(.015f, .015f, .015f, 1f)));
        viewPort.setRenderPath(RenderPath.Forward);
        viewPort.setBackgroundColor(new ColorRGBA(.015f, .015f, .015f, 1f));
        cam.setLocation(new Vector3f(0f, .01f, .51f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        cam.setFrustumPerspective(35f, (float) WIDTH / HEIGHT, .005f, 5f);
        if (verify) {
            detail = detailNormal();
            float[] encoded = new float[256 * 256 * 3];
            ByteBuffer pixels = detail.getImage().getData(0);
            for (int i = 0; i < 256 * 256; i++) {
                for (int c = 0; c < 3; c++) {
                    encoded[i * 3 + c] = (pixels.get(i * 4 + c) & 255) / 255f;
                }
            }
            SkinNormalFilter.Result maps = SkinNormalFilter.generate(encoded, 256, 256,
                    SkinLutGenerator.referenceProfile(), 2f, SkinNormalFilter.Boundary.WRAP, SkinNormalFilter.Quality.EXACT);
            filtered = new Texture2D[]{normalTexture(maps, 0), normalTexture(maps, 1), normalTexture(maps, 2)};
        } else {
            // 第三步：切换散射/法线，观察相同 PBR 参数下的差异。
            inputManager.addMapping("Skin", new KeyTrigger(KeyInput.KEY_F5));
            inputManager.addMapping("Normals", new KeyTrigger(KeyInput.KEY_F6));
            inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
                if (pressed) {
                    if ("Skin".equals(name)) {
                        scattering = !scattering;
                    } else {
                        normals = !normals;
                    }
                    authored.setFloat("SkinStrength", scattering ? 1f : 0f);
                    authored.setVector3("SkinNormalWeights", normals ? authoredNormalWeights : Vector3f.UNIT_XYZ);
                    for (String parameter : new String[]{"SkinDiffuseNormalMap", "SkinRedNormalMap", "SkinGreenNormalMap", "SkinBlueNormalMap"}) {
                        if (normals && authoredNormals.containsKey(parameter)) {
                            authored.setTexture(parameter, authoredNormals.get(parameter));
                        } else {
                            authored.clearParam(parameter);
                        }
                    }
                }
            }, "Skin", "Normals");
        }
    }

    private void bind(String parameter, String key) {
        if (!key.isEmpty()) {
            authored.setTexture(parameter, assetManager.loadTexture(key));
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!verify || frame % 8 != 0) {
            return;
        }
        int phase = frame / 8;
        Material skin = authored.clone();
        viewPort.setRenderPath(RenderPath.Forward);
        renderManager.setGpuSceneEnabled(phase >= 13);
        if (phase == 0) {
            Material pbr = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            for (MatParam parameter : skin.getParams()) {
                if (pbr.getMaterialDef().getMaterialParam(parameter.getName()) != null) {
                    pbr.setParam(parameter.getName(), parameter.getVarType(), parameter.getValue());
                }
            }
            skin = pbr;
        } else if (phase == 1) {
            skin.setFloat("SkinStrength", 0f);
        } else if (phase == 2) {
            skin.clearParam("SkinLut");
        } else if (phase == 3) {
            skin.setTexture("SkinMask", constant(0f, 0f, 0f));
        } else if (phase == 4) {
            skin.setFloat("SkinScatteringScale", 8f);
        } else if (phase == 5) {
            skin.clearParam("SkinRedNormalMap");
            skin.clearParam("SkinGreenNormalMap");
            skin.clearParam("SkinBlueNormalMap");
            skin.clearParam("SkinDiffuseNormalMap");
            skin.setTexture("SkinCurvatureMap", constant(0f, 0f, 0f));
            skin.setVector3("SkinNormalWeights", Vector3f.UNIT_XYZ);
        } else if (phase == 6) {
            skin.setTexture("SkinCurvatureMap", constant(.2f, .2f, .2f));
        }
        if (phase >= 7 && phase <= 14 || phase >= 17) {
            skin.setTexture("NormalMap", detail);
            if (phase == 8) {
                skin.setFloat("SkinNormalMinLod", 0f);
            } else if (phase == 9) {
                skin.setTexture("SkinDiffuseNormalMap", constant(.5f, .5f, 1f));
                skin.setTexture("SkinCurvatureMap", constant(.1f, .1f, .1f));
            }
        }
        if (phase >= 11 && phase <= 13) {
            viewPort.setRenderPath(new RenderPath[]{RenderPath.Deferred,
                RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU}[phase - 11]);
        }
        if (phase == 16) {
            SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 256, 128, 1f, 128).applyTo(skin);
        }
        if (phase >= 17 && phase <= 18) {
            skin.setTexture("SkinRedNormalMap", filtered[0]);
            skin.setTexture("SkinGreenNormalMap", filtered[1]);
            skin.setTexture("SkinBlueNormalMap", filtered[2]);
            if (phase == 18) {
                skin.setFloat("NormalType", -1f);
            }
        } else if (phase == 19) {
            skin.setTexture("SkinDiffuseNormalMap", filtered[0]);
        } else if (phase == 20) {
            skin.setTexture("SkinRedNormalMap", filtered[0]);
        }
        for (Geometry geometry : subjects) {
            geometry.setMaterial(skin);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!verify) {
            return;
        }
        if (frame % 8 == 7) {
            int phase = frame / 8;
            ByteBuffer buffer = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), buffer, Image.Format.RGBA8);
            byte[] pixels = new byte[buffer.capacity()];
            buffer.get(pixels);
            save(pixels, phase);
            if (phase == 0) {
                pbrReference = pixels;
            } else if (phase <= 3 || phase == 5) {
                check(difference(pbrReference, pixels) < .15, "PBR fallback differs, phase " + phase);
            } else if (phase == 4 || phase == 6) {
                check(difference(pbrReference, pixels) > .01, "No curvature response");
            } else if (phase == 10 || phase == 15) {
                pathReference = pixels;
            } else if (phase >= 11 && phase <= 14 || phase == 16) {
                double difference = difference(pathReference, pixels);
                System.out.println("SKIN phase=" + phase + " path=" + viewPort.getRenderPath() + " difference=" + difference);
                check(difference < .2, "Skin render-path/LUT mismatch");
            }
            if (phase == 20) {
                System.out.println("SKIN_V2_PASS");
                stop();
            }
        }
        frame++;
    }

    private static double difference(byte[] a, byte[] b) {
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            if (i % 4 != 3) {
                sum += Math.abs((a[i] & 255) - (b[i] & 255));
            }
        }
        return (double) sum / (WIDTH * HEIGHT * 3);
    }

    private static Texture2D constant(float r, float g, float b) {
        ByteBuffer data = BufferUtils.createByteBuffer(4);
        data.put((byte) Math.round(r * 255f)).put((byte) Math.round(g * 255f)).put((byte) Math.round(b * 255f)).put((byte) 255).flip();
        return new Texture2D(new Image(Image.Format.RGBA8, 1, 1, data, ColorSpace.Linear));
    }

    private static Texture2D normalTexture(SkinNormalFilter.Result normals, int channel) {
        int width = normals.getWidth();
        int height = normals.getHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < 3; c++) {
                    pixels.put((byte) Math.round((normals.get(channel, x, y, c) * .5f + .5f) * 255f));
                }
                pixels.put((byte) 255);
            }
        }
        pixels.flip();
        Texture2D texture = new Texture2D(new Image(Image.Format.RGBA8, width, height, pixels, ColorSpace.Linear));
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        return texture;
    }

    private static Texture2D detailNormal() {
        int size = 256;
        ByteBuffer data = BufferUtils.createByteBuffer(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Vector3f normal = new Vector3f(.3f * FastMath.sin(x * .8f), .3f * FastMath.cos(y * .8f), 1f).normalizeLocal();
                data.put((byte) Math.round((normal.x * .5f + .5f) * 255f));
                data.put((byte) Math.round((normal.y * .5f + .5f) * 255f));
                data.put((byte) Math.round((normal.z * .5f + .5f) * 255f));
                data.put((byte) 255);
            }
        }
        data.flip();
        Texture2D texture = new Texture2D(new Image(Image.Format.RGBA8, size, size, data, ColorSpace.Linear));
        texture.setMinFilter(Texture.MinFilter.Trilinear);
        return texture;
    }

    private void save(byte[] pixels, int phase) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    double linear = (pixels[(y * WIDTH + x) * 4 + c] & 255) / 255.0;
                    double srgb = linear <= .0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1.0 / 2.4) - .055;
                    rgb = (rgb << 8) | (int) Math.round(srgb * 255.0);
                }
                image.setRGB(x, HEIGHT - 1 - y, rgb);
            }
        }
        try {
            String subject = System.getProperty("skin.materialAssets") != null ? "filtered-head"
                    : (System.getProperty("skin.assets") == null ? "sphere" : "head");
            File file = new File("build/skin-v2/images/" + subject + "/phase-" + phase + ".png");
            java.nio.file.Files.createDirectories(file.getParentFile().toPath());
            ImageIO.write(image, "png", file);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void check(boolean pass, String message) {
        if (!pass) {
            throw new AssertionError(message);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}

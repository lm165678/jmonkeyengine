package jme3test.renderpath;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.MorphControl;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.LightProbe;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.instancing.InstancedNode;
import com.jme3.scene.mesh.MorphTarget;
import com.jme3.scene.shape.Box;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import com.jme3.util.TangentBinormalGenerator;

/**
 Exercises native material features through the common surface-writing techniques.
 * <p>Pipeline/FrameGraph usage: run main() from jme3-examples with OpenGL 4.3
 * for GPU culling. Existing scene controls remain available; helper-based scenes
 * use SPACE (terrain scenes: K). Configuration runs on the render thread.</p>
 * <pre>{@code
 * renderManager.setDeferredTileSize(16);
 * useRenderPath(RenderPath.TiledDeferredGPU);
 * // Switch back using the original Forward renderer:
 * useRenderPath(RenderPath.Forward);
 * }</pre>
 * <p>RenderPathHelper.apply shows the full public API: install DeferredPipeline
 * with the shared model registry, select LightCullingMode and tile size, or
 * install OpaqueForwardPipeline. DeferredPipeline appends passes to the viewport FrameGraph and its GBuffer pool.
 * Query its active path and fallback reason after rendering; a GPU request may
 * fall back to CPU or Forward on unsupported contexts/materials.</p>
 * <p>Load materials from .j3md. Custom definitions declare DeferredLightingShader
 * and provide default Forward/GBuffer techniques; application code does not load
 * or register a .frag. See Materials/Tiled/CustomLambert.j3md for a complete asset.</p>
 * <p>Automated check: gradlew :jme3-examples:renderPathSmoke
 * -PrenderPathExample=TestDeferredFeatures. Checks CPU/GPU pixels and Forward round trips;
 * renderPipelineSuite additionally checks direct Pipeline installation.</p>
 */
public class TestDeferredFeatures extends RenderPathExample {
    private int slot;

    @Override
    public void simpleInitApp() {
        cam.setLocation(new Vector3f(0, 2, 22));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(10);
        setDisplayFps(false);
        setDisplayStatView(false);
        renderManager.setPreferredLightMode(com.jme3.material.TechniqueDef.LightMode.SinglePass);
        rootNode.addLight(new AmbientLight(new ColorRGBA(.2f, .2f, .2f, 1)));
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -1, -1).normalizeLocal()));
        LightProbe probe =
            (LightProbe) assetManager.loadAsset("Scenes/LightProbes/quarry_Probe.j3o");
        probe.getArea().setRadius(100);
        rootNode.addLight(probe);
        for (int i = 0; i < 2; i++) {
            LightProbe blended = new LightProbe();
            blended.setPrefilteredMap(probe.getPrefilteredEnvMap());
            blended.setShCoeffs(probe.getShCoeffs());
            blended.setNbMipMaps(probe.getNbMipMaps());
            blended.setReady(true);
            blended.getArea().setRadius(100);
            blended.setPosition(new Vector3f(i == 0 ? -4 : 4, 2, 0));
            rootNode.addLight(blended);
        }
        Texture2D normal = texture(150, 110, 245, 100);
        Texture2D map = texture(180, 200, 160, 255);
        for (String definition : new String[] {"Lighting", "PBRLighting", "Unshaded"}) {
            String folder = definition.equals("Unshaded") ? "Misc" : "Light";
            Material base =
                new Material(assetManager, "Common/MatDefs/" + folder + "/" + definition + ".j3md");
            if (definition.equals("Lighting")) {
                base.setBoolean("UseMaterialColors", true);
                base.setColor("Diffuse", new ColorRGBA(.5f, .7f, .8f, 1));
                base.setColor("Ambient", ColorRGBA.White);
                base.setColor("Specular", ColorRGBA.White);
                base.setFloat("Shininess", 32);
            } else if (definition.equals("PBRLighting")) {
                base.setFloat("Metallic", .25f);
                base.setFloat("Roughness", .45f);
            }
            add(base.clone(), definition);
            Material lightmap = base.clone();
            lightmap.setTexture("LightMap", map);
            lightmap.setBoolean("SeparateTexCoord", true);
            add(lightmap, definition + " lightmap");
            if (!definition.equals("Unshaded")) {
                Material relief = base.clone();
                relief.setTexture("NormalMap", normal);
                relief.setBoolean("PackedNormalParallax", true);
                relief.setBoolean("SteepParallax", true);
                add(relief, definition + " normal + relief");
                if (definition.equals("Lighting")) {
                    Material vertex = base.clone();
                    vertex.setBoolean("VertexLighting", true);
                    add(vertex, "vertex lighting");
                    Material ramp = base.clone();
                    ramp.setTexture("ColorRamp", map);
                    add(ramp, "color ramp");
                } else {
                    Material specGloss = base.clone();
                    specGloss.setBoolean("UseSpecGloss", true);
                    specGloss.setFloat("Glossiness", .6f);
                    specGloss.setTexture("SpecularGlossinessMap", map);
                    specGloss.setColor("Emissive", new ColorRGBA(.1f, .2f, .1f, 1));
                    add(specGloss, "spec gloss + emissive");
                }
            }
            Geometry morph = add(base.clone(), definition + " morph");
            float[] deltas = new float[morph.getMesh().getVertexCount() * 3];
            for (int i = 0; i < deltas.length; i += 3) {
                deltas[i] = i % 2 == 0 ? .3f : -.1f;
            }
            MorphTarget target = new MorphTarget();
            target.setBuffer(VertexBuffer.Type.Position, BufferUtils.createFloatBuffer(deltas));
            morph.getMesh().addMorphTarget(target);
            morph.setMorphState(new float[] {.6f});
            morph.addControl(new MorphControl());

            InstancedNode instances = new InstancedNode(definition + " instances");
            Material instanced = base.clone();
            instanced.setBoolean("UseInstancing", true);
            for (int i = 0; i < 2; i++) {
                Geometry instance = add(instanced, definition + " instance");
                instances.attachChild(instance);
            }
            instances.instance();
            rootNode.attachChild(instances);

            Node animated = (Node) assetManager.loadModel("Models/Oto/Oto.mesh.xml");
            animated.setMaterial(base.clone());
            animated.scale(.12f);
            animated.setLocalTranslation((slot++ % 8 - 3.5f) * 2, 4 - (slot / 8) * 2, 0);
            AnimComposer composer = animated.getControl(AnimComposer.class);
            composer.setCurrentAction("Walk");
            rootNode.attachChild(animated);
        }
        new RenderPathHelper(this);
    }

    private Geometry add(Material material, String name) {
        Box mesh = new Box(.6f, .6f, .6f);
        mesh.setBuffer(VertexBuffer.Type.TexCoord2, 2,
            BufferUtils.clone(mesh.getFloatBuffer(VertexBuffer.Type.TexCoord)));
        TangentBinormalGenerator.generate(mesh);
        Geometry geometry = new Geometry(name, mesh);
        geometry.setMaterial(material);
        geometry.setLocalTranslation((slot % 8 - 3.5f) * 2, 4 - (slot / 8) * 2, 0);
        slot++;
        rootNode.attachChild(geometry);
        return geometry;
    }

    private Texture2D texture(int r, int g, int b, int a) {
        java.nio.ByteBuffer bytes = BufferUtils.createByteBuffer(4);
        bytes.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return new Texture2D(new Image(Image.Format.RGBA8, 1, 1, bytes, ColorSpace.Linear));
    }

    public static void main(String[] args) {
        new TestDeferredFeatures().start();
    }
}

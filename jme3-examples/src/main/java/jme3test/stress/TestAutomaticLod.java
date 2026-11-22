package jme3test.stress;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.LodControl;
import com.jme3.scene.shape.Sphere;
import jme3tools.optimize.LodGenerator;

/**
 * Move toward and away from the sphere with the standard fly camera. The HUD
 * shows the selected LOD. An interactive run needs a desktop renderer.
 * Run {@code gradlew :jme3-examples:automaticLodSmoke} for a CPU-only check
 * of the render callback with near and far cameras.
 */
public final class TestAutomaticLod extends SimpleApplication {
    private Geometry model;
    private com.jme3.font.BitmapText indicator;

    public static void main(String[] args) {
        if (args.length == 1 && "--verify".equals(args[0])) {
            verify();
        } else {
            new TestAutomaticLod().start();
        }
    }

    private static Geometry model() {
        // 第一步：烘焙网格 LOD。SDK 转换生成的 J3O 已包含网格和控件。
        Geometry sphere = new Geometry("LOD sphere", new Sphere(48, 48, 1f));
        new LodGenerator(sphere).bakeLods(LodGenerator.TriangleReductionMethod.PROPORTIONAL, .5f, .75f);
        // 第二步：配置两端可用的最精细一级，再挂到几何体。
        LodControl control = new LodControl();
        control.setDesktopStartLevel(0);
        control.setMobileStartLevel(1);
        sphere.addControl(control);
        return sphere;
    }

    @Override
    public void simpleInitApp() {
        model = model();
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.getAdditionalRenderState().setWireframe(true);
        model.setMaterial(material);
        // 第三步：挂到场景后由视口相机驱动切换。
        rootNode.attachChild(model);
        cam.setLocation(new Vector3f(0f, 0f, 6f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        indicator = new com.jme3.font.BitmapText(guiFont);
        indicator.setLocalTranslation(10, cam.getHeight() - 10, 0);
        guiNode.attachChild(indicator);
    }

    @Override
    public void simpleUpdate(float tpf) {
        indicator.setText("LOD " + model.getLodLevel());
    }

    private static void verify() {
        Geometry model = model();
        model.updateGeometricState();
        Camera camera = new Camera(800, 600);
        camera.setFrustumPerspective(45, 4f / 3f, .1f, 1000);
        ViewPort view = new ViewPort("LOD verification", camera);
        LodControl control = model.getControl(LodControl.class);
        camera.setLocation(new Vector3f(0, 0, 5));
        control.render(null, view);
        if (model.getLodLevel() != 0) {
            throw new AssertionError("Expected desktop LOD0 near the mesh");
        }
        camera.setLocation(new Vector3f(0, 0, 200));
        control.render(null, view);
        if (model.getLodLevel() != model.getMesh().getNumLodLevels() - 1 || model.getLodLevel() == 0) {
            throw new AssertionError("Expected a reduced LOD far from the mesh");
        }
        System.out.println("AUTOMATIC_LOD_PASS");
    }
}

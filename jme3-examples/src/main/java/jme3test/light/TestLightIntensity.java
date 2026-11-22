package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.light.SkyLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Shows light intensity separately from color using an ordinary desktop
 * OpenGL context. Press + or - to double or halve the intensity. The
 * DirectionalLight keeps its base color as the scene gets brighter or darker.
 * Run {@code gradlew :jme3-examples:lightIntensitySmoke} to verify the API
 * and J3O round trip without a graphics context.
 */
public final class TestLightIntensity extends SimpleApplication {
    private final DirectionalLight light = new DirectionalLight(
            new Vector3f(-1f, -2f, -1f).normalizeLocal(), new ColorRGBA(1f, .75f, .45f, 1f));

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "--verify".equals(args[0])) {
            verify();
        } else {
            new TestLightIntensity().start();
        }
    }

    @Override
    public void simpleInitApp() {
        // 第一步：创建一个使用 PBR 材质的白色盒子，并添加暖色方向光。
        Geometry box = new Geometry("White box", new Box(1f, 1f, 1f));
        Material material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        material.setColor("BaseColor", ColorRGBA.White);
        box.setMaterial(material);
        rootNode.attachChild(box);
        rootNode.addLight(light);
        cam.setLocation(new Vector3f(3f, 2f, 4f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        // 第二步：只调强度。调回 1 恢复默认亮度，颜色独立保存。
        inputManager.addMapping("Brighter", new KeyTrigger(KeyInput.KEY_EQUALS));
        inputManager.addMapping("Darker", new KeyTrigger(KeyInput.KEY_MINUS));
        inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, time) -> {
            if (pressed) {
                light.setIntensity(light.getIntensity() * ("Brighter".equals(name) ? 2f : .5f));
            }
        }, "Brighter", "Darker");
    }

    private static void verify() throws IOException {
        var light = new DirectionalLight();
        light.setColor(new ColorRGBA(.4f, .5f, .25f, .8f));
        check(light.getIntensity() == 1f && reload(light).getIntensity() == 1f, "Legacy default");
        light.setIntensity(4f);
        var result = light.getEffectiveColor(new ColorRGBA());
        check(result.equals(new ColorRGBA(1.6f, 2f, 1f, .8f)), "Linear RGB multiplier");
        check(light.getColor().equals(new ColorRGBA(.4f, .5f, .25f, .8f)), "Base color changed");
        var reloaded = reload(light);
        check(reloaded.getIntensity() == 4f && reloaded.getColor().equals(light.getColor()), "J3O round trip");
        check(light.clone().getIntensity() == 4f, "Clone");
        light.setIntensity(0f);
        check(light.getEffectiveColor(result).equals(new ColorRGBA(0f, 0f, 0f, .8f)), "Zero contribution");
        var sky = new SkyLight();
        sky.setIntensity(2.5f);
        check(reload(sky).getIntensity() == 2.5f, "Sky intensity J3O field");
        System.out.println("LIGHT_INTENSITY_PASS");
    }

    private static com.jme3.light.Light reload(com.jme3.light.Light light) throws IOException {
        var data = new ByteArrayOutputStream();
        new BinaryExporter().save(light, data);
        return (com.jme3.light.Light) new BinaryImporter().load(new ByteArrayInputStream(data.toByteArray()));
    }

    private static void check(boolean passed, String description) {
        if (!passed) {
            throw new AssertionError(description);
        }
    }
}

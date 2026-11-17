/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 */
package jme3test.material;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;

/**
 * Verifies the built-in Lighting and PBRLighting shader-node templates in the
 * forward MultiPass renderer. No additional capabilities are required.
 *
 * 第一步：运行本类，左侧球体使用 LightingNodes.j3md。
 * 第二步：观察右侧使用 PBRLightingNodes.j3md 的金属度/粗糙度高光。
 * 第三步：拖动鼠标旋转相机，滚轮调整距离，检查法线、视角和光照变化。
 *
 * Both templates can also be opened in the SDK Material Definition Editor.
 * Automated compile verification from the repository root:
 * {@code ./gradlew.bat :jme3-examples:compileJava}
 * Shader compilation and visual output still require launching this example on
 * a working GPU; the Java compile task does not validate GLSL.
 */
public class TestLightingShaderNodes extends SimpleApplication {

    public static void main(String[] args) {
        TestLightingShaderNodes application = new TestLightingShaderNodes();
        application.start();
    }

    @Override
    public void simpleInitApp() {
        Sphere sphereMesh = new Sphere(32, 32, 1.0f);

        Material phongMaterial = assetManager.loadMaterial(
                "Common/MatDefs/Misc/LightingNodes.j3md");
        phongMaterial.setColor("Diffuse", ColorRGBA.Blue);
        phongMaterial.setColor("Specular", ColorRGBA.White);
        phongMaterial.setColor("Ambient", new ColorRGBA(0.2f, 0.2f, 0.2f, 1.0f));
        phongMaterial.setFloat("Shininess", 24.0f);
        Geometry phongSphere = new Geometry("Lighting shader nodes", sphereMesh);
        phongSphere.setLocalTranslation(-1.5f, 0.0f, 0.0f);
        phongSphere.setMaterial(phongMaterial);
        rootNode.attachChild(phongSphere);

        Material pbrMaterial = assetManager.loadMaterial(
                "Common/MatDefs/Misc/PBRLightingNodes.j3md");
        pbrMaterial.setColor("BaseColor", new ColorRGBA(0.75f, 0.32f, 0.12f, 1.0f));
        pbrMaterial.setFloat("Metallic", 0.35f);
        pbrMaterial.setFloat("Roughness", 0.28f);
        Geometry pbrSphere = new Geometry("PBR shader nodes", sphereMesh);
        pbrSphere.setLocalTranslation(1.5f, 0.0f, 0.0f);
        pbrSphere.setMaterial(pbrMaterial);
        rootNode.attachChild(pbrSphere);

        DirectionalLight keyLight = new DirectionalLight();
        keyLight.setDirection(new Vector3f(-1.0f, -0.8f, -1.0f).normalizeLocal());
        keyLight.setColor(ColorRGBA.White.mult(1.2f));
        rootNode.addLight(keyLight);

        DirectionalLight fillLight = new DirectionalLight();
        fillLight.setDirection(new Vector3f(1.0f, -0.25f, 0.5f).normalizeLocal());
        fillLight.setColor(new ColorRGBA(0.3f, 0.45f, 0.65f, 1.0f));
        rootNode.addLight(fillLight);

        PointLight pointLight = new PointLight();
        pointLight.setPosition(new Vector3f(-2.0f, 2.0f, 2.0f));
        pointLight.setColor(new ColorRGBA(1.0f, 0.55f, 0.25f, 1.0f));
        pointLight.setRadius(7.0f);
        rootNode.addLight(pointLight);

        SpotLight spotLight = new SpotLight();
        spotLight.setPosition(new Vector3f(2.0f, 2.5f, 3.0f));
        spotLight.setDirection(new Vector3f(0.0f, -0.4f, -1.0f).normalizeLocal());
        spotLight.setColor(ColorRGBA.White.mult(1.5f));
        spotLight.setSpotRange(8.0f);
        spotLight.setSpotInnerAngle(FastMath.DEG_TO_RAD * 12.0f);
        spotLight.setSpotOuterAngle(FastMath.DEG_TO_RAD * 28.0f);
        rootNode.addLight(spotLight);

        AmbientLight ambientLight = new AmbientLight();
        ambientLight.setColor(ColorRGBA.White.mult(0.2f));
        rootNode.addLight(ambientLight);

        cam.setLocation(new Vector3f(0.0f, 0.8f, 5.5f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(3.0f);
        flyCam.setRotationSpeed(FastMath.QUARTER_PI);
    }
}

package jme3test.anim;

import com.jme3.anim.*;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.util.Arrays;
import java.util.Collections;

/**
 * Runnable animation-controller example, with generated clips (no external assets).
 * Entry point: jme3test.anim.TestAnimationGraph. Requires desktop OpenGL; no compute
 * or skinning extensions. Left/right change Speed; Space triggers a one-shot jump.
 * The SDK can edit the same definition stored in AnimationGraph.USER_DATA in J3O.
 * Key APIs: AnimationGraph, AnimationGraphPlayer.attach, setFloat, trigger, close.
 * Automated runtime verification: gradlew :jme3-core:test
 * --tests com.jme3.anim.graph.AnimationGraphTest
 * <pre>
 * 第一步：构造或加载带 AnimComposer 的模型，动画片段可来自 glTF 转换的 j3o。
 * 第二步：创建 Speed 参数、1D 混合状态以及 Jump 触发器与过渡。
 * 第三步：把 graph.encode() 写入模型 userData；SDK Save J3O 也使用相同格式。
 * 第四步：在游戏更新线程 attach，输入逻辑只更新参数，不逐个切换动画。
 * 第五步：销毁角色前 close 恢复原动画层；每个模型克隆单独 attach。
 * </pre>
 * Default Speed is zero. Locomotion loops; Jump holds its last pose until its
 * exit-time edge returns. Transitions last 0.2 seconds and cannot be interrupted.
 */
public final class TestAnimationGraph extends SimpleApplication {
    private AnimationGraphPlayer player;
    private float speed;

    public static void main(String[] args) {
        new TestAnimationGraph().start();
    }

    @Override
    public void simpleInitApp() {
        Node character = new Node("Animated cube");
        Geometry cube = new Geometry("Cube", new Box(0.4f, 0.4f, 0.4f));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.Cyan);
        cube.setMaterial(material);
        character.attachChild(cube);
        AnimComposer composer = new AnimComposer();
        character.addControl(composer);
        addClip(composer, cube, "Idle", new Vector3f(0, 0, 0));
        addClip(composer, cube, "Walk", new Vector3f(1, 0.3f, 0));
        addClip(composer, cube, "Run", new Vector3f(2, 0.6f, 0));
        addClip(composer, cube, "Jump", new Vector3f(0, 2, 0));
        AnimationGraph graph = new AnimationGraph("Locomotion",
                Arrays.asList(new Parameter("Speed", Type.FLOAT, 0), new Parameter("Jump", Type.TRIGGER, 0)),
                Arrays.asList(new State("Locomotion", "Speed", Arrays.asList("Idle", "Walk", "Run"),
                        new float[]{0, 1, 2}, true, 40, 40),
                        new State("Jump", "", Collections.singletonList("Jump"), new float[]{0}, false, 300, 40)),
                Arrays.asList(new Transition("Locomotion", "Jump", "Jump", Operator.TRIGGER, 0, 0.2f, -1),
                        new Transition("Jump", "Locomotion", "", Operator.ALWAYS, 0, 0.2f, 1)));
        character.setUserData(AnimationGraph.USER_DATA, graph.encode());
        player = AnimationGraphPlayer.attach(character);
        rootNode.attachChild(character);
        inputManager.addMapping("Slower", new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping("Faster", new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping("Jump", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("Jump")) {
                player.trigger("Jump");
            } else {
                speed = Math.max(0, Math.min(2, speed + (name.equals("Faster") ? 0.2f : -0.2f)));
                player.setFloat("Speed", speed);
            }
        }, "Slower", "Faster", "Jump");
        flyCam.setEnabled(false);
        cam.setLocation(new Vector3f(3, 3, 8));
        cam.lookAt(new Vector3f(0, 1, 0), Vector3f.UNIT_Y);
    }

    private static void addClip(AnimComposer composer, Geometry target, String name, Vector3f peak) {
        AnimClip clip = new AnimClip(name);
        clip.setTracks(new AnimTrack[]{new TransformTrack(target, new float[]{0, 0.5f, 1},
                new Vector3f[]{new Vector3f(), peak, new Vector3f()}, null, null)});
        composer.addAnimClip(clip);
    }
}

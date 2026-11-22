package com.jme3.scene;

import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.renderer.Camera;
import java.io.IOException;

/**
 * A camera start transform without a mesh. Higher priority IDs win. For equal
 * IDs the last Start in depth-first postorder wins (children in scene order,
 * then their parent). Camera forward is local +Z and up is local +Y.
 *
 * <pre>{@code
 * // 第一步：在 SDK 的 Spawn / Spatial 拖入 Start，移动或旋转它，设置 Priority ID（默认 0）后保存 J3O。
 * // 第二步：在 SimpleApplication 的 simpleInitApp 或渲染线程加载并挂载场景。
 * Spatial scene = assetManager.loadModel("Scenes/Level.j3o");
 * rootNode.attachChild(scene);
 * // 默认在首次渲染前应用，游戏中移动相机不会持续重置相机。
 * // 第三步：自己管理相机时可关闭自动应用，按需选择并应用。
 * getSceneServices().setStartCameraEnabled(false);
 * Start.applyToCamera(scene, cam);
 * }</pre>
 * Loading alone leaves the camera untouched. If no Start exists, the current
 * camera transform is preserved. Scale and projection are independent.
 */
public class Start extends Node {
    private int priorityId;

    /** Creates a Start at the identity transform with priority ID zero. */
    public Start() {
        super("Start");
    }

    /** Returns the signed priority used to select the initial camera pose. */
    public int getPriorityId() {
        return priorityId;
    }

    /** Sets the priority. Higher values take precedence. */
    public void setPriorityId(int priorityId) {
        this.priorityId = priorityId;
    }

    /**
     * Copies this Start's world position and rotation to the camera.
     * Call on the thread that owns the scene and camera.
     */
    public void positionCamera(Camera camera) {
        camera.setLocation(getWorldTranslation());
        camera.setRotation(getWorldRotation());
    }

    /**
     * Selects and applies a Start from the supplied scene, including its root.
     * Returns false when the scene contains no Start.
     */
    public static boolean applyToCamera(Spatial scene, Camera camera) {
        Start[] selected = {null};
        scene.depthFirstTraversal(spatial -> {
            if (spatial instanceof Start) {
                Start candidate = (Start) spatial;
                if (selected[0] == null || candidate.priorityId >= selected[0].priorityId) {
                    selected[0] = candidate;
                }
            }
        });
        if (selected[0] == null) {
            return false;
        }
        selected[0].positionCamera(camera);
        return true;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        exporter.getCapsule(this).write(priorityId, "priorityId", 0);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        priorityId = importer.getCapsule(this).readInt("priorityId", 0);
    }
}

package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.light.DirectionalLight;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;

/**
 * Incremental registration/streaming regression with 100,000 real Tank models.
 * Entry: TestGpuSceneStreaming.main. Requires OpenGL 4.3. Verification command:
 * gradlew :jme3-examples:gpuStreamingCheck. Automatically tests unloading,
 * reattaching, same-frame reparenting, edits to a registered material, and moving
 * a parent. Exits on success; an exception fails the Gradle task. No asset writes.
 * The loader/IO policy belongs to the application; this tests GPU residency after
 * scene attach/detach, not an invented engine-specific world-file format.
 * <pre>{@code
 * // 第一步：后台加载 j3o；在应用线程挂载场景块，自动注册。
 * app.enqueue(() -> {

 *     world.attachChild(chunk);

 *     return null;

 * });
 * // 第二步：卸载或重挂载使用普通场景图 API，不需要全场景 rebuild。
 * world.detachChild(chunk);
 * otherParent.attachChild(chunk);
 * // 第三步：参数/网格/队列策略变更后，只刷新这个子树。
 * pipeline.refreshSubtree(chunk);
 * // 第四步：父节点运动沿用 JME 变换继承，只有脏实例更新。
 * chunk.move(1, 0, 0);
 * }</pre>
 */
public final class TestGpuSceneStreaming extends SimpleApplication {
    private final Node world = new Node("Resident world");
    private final Node otherParent = new Node("Reparent destination");
    private Node chunk;
    private GpuScenePipeline pipeline;
    private int frame;
    private int initial;
    private int removed;
    private long initialRegistrations;
    private long initialBatchBuilds;

    public static void main(String[] args) {
        TestGpuSceneStreaming app = new TestGpuSceneStreaming();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setResolution(640, 480);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        Spatial template = assetManager.loadModel("Models/Tank/LodTank.j3o");
        rootNode.attachChild(world);
        world.attachChild(otherParent);
        for (int block = 0; block < 100; block++) {
            Node node = new Node("Chunk " + block);
            world.attachChild(node);
            if (block == 0) {
                chunk = node;
            }
            for (int i = 0; i < 1000; i++) {
                Spatial model = template.clone(false);
                model.setLocalScale(.05f);
                model.setLocalTranslation((i % 32) * 20, 0, -(i / 32) * 20 - block * 1000);
                node.attachChild(model);
            }
        }
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal()));
        cam.setLocation(new Vector3f(0, 100, 100));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        pipeline = new GpuScenePipeline(assetManager, world);
        pipeline.setEnabled(true);
        pipeline.setMultiDrawIndirect(true);
        pipeline.setMeshMerging(true);
        pipeline.setMaterialMerging(true);
        pipeline.setMaxDistance(500);
        pipeline.setLodThresholds(80, 30, 10, 3);
        renderManager.setPipeline(viewPort, pipeline);
        removed = geometryCount(chunk);
    }

    private int geometryCount(Spatial spatial) {
        if (spatial instanceof Geometry) {
            return 1;
        }
        int count = 0;
        for (Spatial child : ((Node) spatial).getChildren()) {
            count += geometryCount(child);
        }
        return count;
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        if (frame == 5) {
            chunk.removeFromParent();
        } else if (frame == 8) {
            world.attachChild(chunk);
        } else if (frame == 11) {
            otherParent.attachChild(chunk);
        } else if (frame == 14) {
            pipeline.refreshSubtree(chunk);
        } else if (frame == 17) {
            chunk.move(10, 0, 0);
        } else if (frame == 20) {
            chunk.removeFromParent();
            world.attachChild(chunk);
            otherParent.attachChild(chunk);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        int count = pipeline.getInstanceCount();
        if (frame == 3) {
            initial = count;
            initialRegistrations = pipeline.getFullRegistrationCount();
            initialBatchBuilds = pipeline.getBatchBuildCount();
            if (initial < 100000) {
                throw new AssertionError("Missing model registration");
            }
        }
        if (frame == 6 && count != initial - removed) {
            throw new AssertionError("Unloading failed: " + count);
        }
        if ((frame == 9 || frame == 12 || frame == 15 || frame == 18 || frame == 21)
                && count != initial) {
            throw new AssertionError("Incremental registration failed at " + frame + ": " + count);
        }
        if (frame == 22) {
            if (pipeline.getFullRegistrationCount() != initialRegistrations
                    || pipeline.getBatchBuildCount() <= initialBatchBuilds) {
                throw new AssertionError("Streaming rebuilt the whole scene or skipped affected batches");
            }
            System.out.println("GPU_STREAMING_PASS geometries=" + count + " removed=" + removed
                    + " fullRegistrations=" + pipeline.getFullRegistrationCount()
                    + " batchBuilds=" + pipeline.getBatchBuildCount());
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}

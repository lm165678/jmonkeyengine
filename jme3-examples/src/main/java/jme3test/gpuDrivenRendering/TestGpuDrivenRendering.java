package jme3test.gpuDrivenRendering;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;

/**
 * GPU indirect integration on Boat/Buggy/Tank model assets, not primitive proxies.
 * OpenGL 4.3 required. Entry: TestGpuDrivenRendering.main; verification:
 * gradlew :jme3-examples:gpuDrivenSmoke. Space toggles GPU scene/original rendering;
 * F2 distance culling, F3 frustum, F4 screen size, F5 Hi-Z. Drag+WASD controls camera.
 * Source material definitions provide all shading. Use --deferred to exercise the
 * same GPU draw packets through DeferredPipeline; no diagnostic surface replacement.
 * Smoke verification checks culling counts, disable/re-enable, Hi-Z switching,
 * parent-transform scatter updates and the rendered image against the original
 * path. Pixel readback occurs only in smoke mode, never in the performance example.
 * --bindless additionally checks resident textures, mutable sampler/image refresh,
 * switching back to ordinary drawing, and shutdown with live handles. --mdi checks
 * shared-index multi-draw. --without-bindless simulates missing driver support.
 * <pre>{@code
 * // 第一步：加载模型并 clone(false)，共享 mesh/material 才能组成同一批实例。
 * Spatial model = assetManager.loadModel("Models/Tank/tank.j3o");
 * models.attachChild(model.clone(false));
 * // 第二步：显式安装并开启；默认关闭，不影响旧项目。
 * GpuScenePipeline pipeline = new GpuScenePipeline(assetManager, models);
 * renderManager.setPipeline(viewPort, pipeline);
 * pipeline.setEnabled(true);
 * // 可选 MDI 回归：以 --smoke --mdi 运行，仍验证与原始材质绘制的像素一致性。
 * // 第三步：按需开启距离和屏幕尺寸剔除，0 表示关闭；视锥默认开启。
 * pipeline.setMaxDistance(100);
 * pipeline.setMinPixels(2);
 * // 第四步：关闭恢复源材质并释放专用缓冲；重新开启会重新创建。
 * pipeline.setEnabled(false);
 * // 第五步：最后恢复原视口管线；只在渲染线程调用。
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class TestGpuDrivenRendering extends SimpleApplication {
    private final Node models = new Node("GPU models");
    private GpuScenePipeline pipeline;
    private boolean smoke;
    private boolean deferred;
    private boolean mdi;
    private boolean bindless;
    private boolean simulateNoBindless;
    private int frame;
    private int allVisible;
    private boolean distance;
    private boolean frustum = true;
    private boolean size;
    private boolean hiz;
    private java.nio.ByteBuffer gpuImage;
    private long fullDetailTriangles;

    public static void main(String[] args) {
        TestGpuDrivenRendering app = new TestGpuDrivenRendering();
        app.simulateNoBindless = java.util.Arrays.asList(args).contains("--without-bindless");
        app.bindless = java.util.Arrays.asList(args).contains("--bindless");
        app.mdi = java.util.Arrays.asList(args).contains("--mdi");
        app.deferred = java.util.Arrays.asList(args).contains("--deferred");
        app.smoke = java.util.Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(1000, 700);
        settings.setVSync(false);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        if (simulateNoBindless) {
            // Test only: exercise unsupported-hardware fallback in this renderer context.
            renderer.getCaps().remove(com.jme3.renderer.Caps.BindlessTextures);
        }
        if (smoke) {
            setDisplayFps(false);
            setDisplayStatView(false);
        }
        rootNode.attachChild(models);
        String[] paths = {"Models/Tank/LodTank.j3o", "Models/Boat/LodBoat.j3o", "Models/Buggy/LodBuggy.j3o"};
        for (int type = 0; type < paths.length; type++) {
            Spatial source = assetManager.loadModel(paths[type]);
            source.updateGeometricState();
            float radius = ((BoundingBox) source.getWorldBound()).getExtent(null).length();
            for (int i = 0; i < 144; i++) {
                Spatial instance = source.clone(false);
                instance.setLocalScale(2 / radius);
                instance.setLocalTranslation((i % 12 - 6) * 6, type * 5, (i / 12 - 6) * 6);
                instance.rotate(0, i * .2f, 0);
                models.attachChild(instance);
            }
        }
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -1, -1).normalizeLocal()));
        cam.setLocation(new Vector3f(0, 18, 60));
        cam.lookAt(new Vector3f(0, 5, 0), Vector3f.UNIT_Y);
        cam.setFrustumFar(400);
        flyCam.setMoveSpeed(30);
        flyCam.setDragToRotate(true);
        pipeline = new GpuScenePipeline(assetManager, models, deferred
                ? new com.jme3.renderer.deferred.DeferredPipeline(assetManager, renderManager.getDeferredShadingModels())
                : new com.jme3.renderer.pipeline.OpaqueForwardPipeline());
        renderManager.setPipeline(viewPort, pipeline);
        pipeline.setEnabled(true);
        pipeline.setMultiDrawIndirect(mdi);
        pipeline.setBindlessTextures(bindless);
        inputManager.addMapping("GPU", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("Distance", new KeyTrigger(KeyInput.KEY_F2));
        inputManager.addMapping("Frustum", new KeyTrigger(KeyInput.KEY_F3));
        inputManager.addMapping("Size", new KeyTrigger(KeyInput.KEY_F4));
        inputManager.addMapping("HiZ", new KeyTrigger(KeyInput.KEY_F5));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("GPU")) {
                pipeline.setEnabled(!pipeline.isEnabled());
            } else if (name.equals("Distance")) {
                distance = !distance;
                pipeline.setMaxDistance(distance ? 40 : 0);
            } else if (name.equals("Frustum")) {
                frustum = !frustum;
                pipeline.setFrustumCulling(frustum);
            } else if (name.equals("Size")) {
                size = !size;
                pipeline.setMinPixels(size ? 35 : 0);
            } else if (name.equals("HiZ")) {
                hiz = !hiz;
                pipeline.setOcclusionCulling(hiz);
            }
        }, "GPU", "Distance", "Frustum", "Size", "HiZ");
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        if (!smoke) {
            return;
        }
        pipeline.setFrustumCulling(false);
        pipeline.setMaxDistance(frame == 3 ? .01f : 0);
        pipeline.setMinPixels(frame == 4 ? 1000000 : 0);
        if (frame == 5) {
            pipeline.setFrustumCulling(true);
            cam.lookAt(cam.getLocation().add(0, 0, 100), Vector3f.UNIT_Y);
        }
        if (frame == 6) {
            pipeline.setEnabled(false);
        }
        if (frame == 7) {
            pipeline.setEnabled(true);
        }
        if (frame == 8) {
            cam.lookAt(new Vector3f(0, 5, 0), Vector3f.UNIT_Y);
            pipeline.setOcclusionCulling(true);
        }
        if (frame == 9) {
            pipeline.setOcclusionCulling(false);
        }
        if (frame == 10) {
            models.setLocalTranslation(10000, 0, 0);
            pipeline.setMaxDistance(100);
        }
        if (frame == 11) {
            models.setLocalTranslation(0, 0, 0);
        }
        if (frame == 12) {
            pipeline.setEnabled(false);
        }
        if (frame == 13) {
            pipeline.setEnabled(true);
            pipeline.setLodThresholds(100000, 50000, 10000, 1000);
        }
        if (frame == 14) {
            pipeline.setLodThresholds();
        }
        if (bindless && frame == 15) {
            java.util.Set<com.jme3.texture.Texture> touched = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            mutateTextures(models, touched);
        }
        if (bindless && frame == 17) {
            pipeline.setEnabled(false);
        }
        if (bindless && frame == 18) {
            pipeline.setEnabled(true);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!smoke) {
            return;
        }
        int count = pipeline.readVisibleCount();
        if (frame == 2) {
            allVisible = count;
            if (count < 432) {
                throw new AssertionError("Missing GPU model instances: " + count);
            }
        }
        if (frame >= 3 && frame <= 6 && count != 0) {
            throw new AssertionError("Culling/toggle failed frame=" + frame + " visible=" + count);
        }
        if (frame == 7) {
            if (count != allVisible) {
                throw new AssertionError("Re-enable lost instances");
            }
        }
        if (frame == 8 && (count <= 0 || count > allVisible)) {
            throw new AssertionError("Invalid Hi-Z visibility: " + count);
        }
        if (frame == 9) {
            if (count != allVisible) {
                throw new AssertionError("Disabling Hi-Z did not restore all instances");
            }
        }
        if (frame == 10 && count != 0) {
            throw new AssertionError("GPU scatter did not update moved bounds");
        }
        if (frame == 11) {
            if (count != allVisible) {
                throw new AssertionError("GPU scatter did not restore moved instances");
            }
            gpuImage = com.jme3.util.BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBufferWithFormat(null, gpuImage, com.jme3.texture.Image.Format.RGBA8);
            fullDetailTriangles = pipeline.readVisibleTriangleCount();
        }
        if (frame == 12) {
            java.nio.ByteBuffer reference = com.jme3.util.BufferUtils.createByteBuffer(gpuImage.capacity());
            renderer.readFrameBufferWithFormat(null, reference, com.jme3.texture.Image.Format.RGBA8);
            int changed = 0;
            int covered = 0;
            for (int i = 0; i < reference.capacity(); i += 4) {
                int difference = 0;
                int brightness = 0;
                for (int channel = 0; channel < 3; channel++) {
                    int original = reference.get(i + channel) & 255;
                    int indirect = gpuImage.get(i + channel) & 255;
                    brightness += original;
                    difference = Math.max(difference, Math.abs(original - indirect));
                }
                if (brightness > 30) {
                    covered++;
                }
                if (difference > 12) {
                    changed++;
                }
            }
            if (covered < 1000 || changed > covered * .02) {
                throw new AssertionError("GPU/original image mismatch: changed=" + changed + " covered=" + covered);
            }
            if (bindless && renderer.getCaps().contains(com.jme3.renderer.Caps.BindlessTextures)
                    && renderer.getBindlessTextureCount() == 0) {
                throw new AssertionError("Bindless requested but no textures became resident");
            }
            System.out.println("BINDLESS_RESIDENT count=" + renderer.getBindlessTextureCount());
            System.out.println("GPU_DRIVEN_PASS instances=" + allVisible
                    + " Hi-Z/movement verified; pixel differences=" + changed + "/" + covered);
        }
        if (frame == 13) {
            long reduced = pipeline.readVisibleTriangleCount();
            if (count != allVisible || reduced <= 0 || reduced >= fullDetailTriangles) {
                throw new AssertionError("LOD compaction: instances=" + count + "/" + allVisible
                        + " triangles=" + reduced + "/" + fullDetailTriangles);
            }
            System.out.println("GPU_LOD_PASS triangles=" + fullDetailTriangles + " -> " + reduced);
        }
        if (frame == 14) {
            if (count != allVisible || pipeline.readVisibleTriangleCount() != fullDetailTriangles) {
                throw new AssertionError("Disabling LOD failed to restore original detail");
            }
            if (!bindless) {
                stop();
            }
        }
        if (bindless && frame == 16) {
            renderer.readFrameBufferWithFormat(null, gpuImage, com.jme3.texture.Image.Format.RGBA8);
        }
        if (bindless && frame == 17) {
            java.nio.ByteBuffer reference = com.jme3.util.BufferUtils.createByteBuffer(gpuImage.capacity());
            renderer.readFrameBufferWithFormat(null, reference, com.jme3.texture.Image.Format.RGBA8);
            int changed = 0;
            for (int i = 0; i < reference.capacity(); i += 4) {
                for (int channel = 0; channel < 3; channel++) {
                    if (Math.abs((reference.get(i + channel) & 255) - (gpuImage.get(i + channel) & 255)) > 12) {
                        changed++;
                        break;
                    }
                }
            }
            if (changed > cam.getWidth() * cam.getHeight() / 100) {
                throw new AssertionError("Texture mutation/restoration mismatch: " + changed);
            }
            System.out.println("BINDLESS_MUTATION_PASS pixel differences=" + changed);
        }
        if (bindless && frame == 18) {
            stop();
        }
    }

    private void mutateTextures(Spatial spatial, java.util.Set<com.jme3.texture.Texture> touched) {
        if (spatial instanceof com.jme3.scene.Geometry) {
            for (com.jme3.material.MatParam parameter : ((com.jme3.scene.Geometry) spatial).getMaterial().getParams()) {
                if (parameter instanceof com.jme3.material.MatParamTexture) {
                    com.jme3.texture.Texture texture = ((com.jme3.material.MatParamTexture) parameter).getTextureValue();
                    if (touched.add(texture)) {
                        texture.setMagFilter(texture.getMagFilter() == com.jme3.texture.Texture.MagFilter.Bilinear
                                ? com.jme3.texture.Texture.MagFilter.Nearest : com.jme3.texture.Texture.MagFilter.Bilinear);
                        texture.getImage().setUpdateNeeded();
                    }
                }
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                mutateTextures(child, touched);
            }
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }
}

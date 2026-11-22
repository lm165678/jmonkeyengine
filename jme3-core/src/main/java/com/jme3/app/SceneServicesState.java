package com.jme3.app;

import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
import com.jme3.post.WorldSettings.DisplayOutput;
import com.jme3.renderer.Limits;
import com.jme3.scene.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Automatic runtime services for the SimpleApplication world root. Loading an
 * asset alone has no side effects: attaching it to the root activates services
 * on the next application frame. Detached scenes release their registrations.
 * Attached WorldSettings automatically restore the main viewport post-processing
 * chain; manual FPPs take precedence. Removing the scene releases the owned chain.
 * Serialized GI volumes create a lazy page streamer; PhysicsControl instances
 * create/reuse a BulletAppState when an optional Bullet implementation is present.
 * A selected {@link Start} supplies the initial main camera transform.
 * Existing physics-space assignments are never stolen. Custom controls, sounds
 * and animation clips are not automatically started. SkyLightControl and managed
 * light shadows retain their own existing renderer lifecycle.
 * Adaptive refinement children are ordinary serialized GiProbeVolume nodes:
 * discovery, external payload loading and shader binding are automatic too.
 * Regular baked grids can embed coarse data and a fine brick directory. The
 * attached world's GI settings govern fine residency and GPU atlas size.
 * Ship every referenced .giprobe asset with the J3O. Loading never rebakes GI.
 * Service membership is cached and refreshed after attachment, detachment,
 * material or control changes through normal scene APIs. Moving objects does not
 * rescan the world. Existing GI volumes still update their world positions and
 * payload preferences each frame, including volumes populated after attachment.
 * Disabling services releases discovery references and listeners; re-enabling
 * discovers the currently attached scene again on the next update.
 *
 * <p>Default GI budget is 256 MiB (including page CPU mirrors), preload distance
 * 200 world units, unload multiplier 1.3, upload budget 1 MiB / 2 ms (soft).
 * At most 16 volumes are active. External GI assets must ship alongside J3O.
 * Embedded bake payloads remain scene-owned even when evicted from GPU memory.
 * Headless renderers without texture limits leave GI inactive; physics still runs.
 * All configuration, attachment and queries below belong on the application
 * thread. Worker threads only load/decompress immutable external payloads.
 *
 * <pre>{@code
 * // 第一步：普通加载并挂载即可；不创建 GI 或物理管理器。
 * rootNode.attachChild(assetManager.loadModel("Scenes/World.j3o"));
 * // 挂载/移除、setMaterial、addControl/removeControl 自动刷新发现缓存。
 * // 仅移动模型不会扫描整棵场景树；已有 GI 体积的运行时属性仍逐帧检查。
 * // 第二步：下一帧服务按需创建后，可以读取并调整现有管理对象。
 * SceneServicesState services = getSceneServices();
 * GiProbeStreamer gi = services.getGiStreamer(); // 尚未出现 GI 时返回 null
 * if (gi != null) {
 *     gi.setUploadBudget(512 * 1024, 1_000_000);
 * }
 * // 若应用依赖 Bullet：stateManager.getState(BulletAppState.class)。
 * // 第三步：切回手动管理前禁用自动服务；恢复开启会重新扫描场景。
 * services.setEnabled(false);
 * services.setEnabled(true);
 * // 第四步：移除场景后自动注销；应用销毁时释放自建线程池和服务。
 * }</pre>
 * GI bindings exist only during rendering and are restored in a finally block,
 * so saving a J3O never serializes the transient multi-volume atlas. Physics
 * controls remain registered while attached; disabled controls remain disabled.
 */
public final class SceneServicesState extends AbstractAppState {
    private SimpleApplication app;
    private Start activeStart;
    private boolean startCameraEnabled = true;
    private final com.jme3.post.WorldSettingsRuntime worldSettings = new com.jme3.post.WorldSettingsRuntime();
    private DisplayOutput displayOutput = DisplayOutput.Application;
    private final ScenePhysicsBridge physics = new ScenePhysicsBridge();
    private final SceneServiceCatalog catalog = new SceneServiceCatalog();
    private final Map<GiProbeVolume, Source> sources = new IdentityHashMap<>();
    private final Set<Material> bound = Collections.newSetFromMap(new IdentityHashMap<>());
    private GiProbeStreamer gi;
    private ExecutorService io;
    private long sequence;
    private int giAtlasMiB;
    private boolean giBricks;

    private static final class Source {
        String id;
        GiProbeData data;
        GiProbeStreamingData bricks;
        String asset;
        Vector3f offset;
    }

    @Override
    public void initialize(AppStateManager manager, Application application) {
        super.initialize(manager, application);
        app = (SimpleApplication) application;
    }

    /** Created when a valid volume is attached. The scene keeps ownership of the bake data. */
    public GiProbeStreamer getGiStreamer() {
        return gi;
    }

    @Override
    public void update(float tpf) {
        catalog.refresh(app.getRootNode());
        physics.update(app, catalog.controls);
        WorldSettings selected = selectWorld();
        worldSettings.sync(selected, app.getAssetManager(), app.getViewPort(), app.getRenderer().getCaps());
        displayOutput = selected == null ? DisplayOutput.Application : selected.getDisplayOutput();
    }

    private WorldSettings selectWorld() {
        WorldSettings selected = null;
        for (WorldSettings candidate : catalog.worlds) {
            if (selected == null || candidate.getPriority() > selected.getPriority()) {
                selected = candidate;
            }
        }
        return selected;
    }

    /**
     * Returns the attached world's display mode after scene services update.
     * With no configured world or disabled services, use the application's launch setting.
     *
     * @return current scene display mode on the application thread
     * @see com.jme3.post.WorldSettings#setDisplayOutput(DisplayOutput)
     */
    public DisplayOutput getDisplayOutput() {
        return displayOutput;
    }

    /**
     * Controls camera placement from attached Start nodes on the render thread.
     * Enabled by default. A different winning Start is applied once, before
     * drawing. Re-enabling applies the current winner on the next render.
     * See {@link com.jme3.scene.Start} for the editor and Java usage.
     */
    public void setStartCameraEnabled(boolean enabled) {
        if (startCameraEnabled != enabled) {
            startCameraEnabled = enabled;
            activeStart = null;
        }
    }

    private void updateStartCamera() {
        if (!startCameraEnabled) {
            return;
        }
        Start selected = null;
        for (Start candidate : catalog.starts) {
            if (selected == null || candidate.getPriorityId() >= selected.getPriorityId()) {
                selected = candidate;
            }
        }
        if (selected != activeStart) {
            activeStart = selected;
            if (selected != null) {
                selected.positionCamera(app.getCamera());
            }
        }
    }

    /** Called by SimpleApplication after the scene's geometric update. */
    void prepareRender() {
        if (!isEnabled() || !isInitialized()) {
            return;
        }
        catalog.refresh(app.getRootNode());
        updateStartCamera();
        Integer textureLimit = app.getRenderer().getLimits().get(Limits.TextureSize);
        if (textureLimit == null || textureLimit < 16) {
            closeGi();
            return;
        }
        Set<GiProbeVolume> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean bricks = false;
        for (GiProbeVolume volume : catalog.volumes) {
            GiProbeStreamingData streaming = volume.getStreamingData();
            if (volume.getData() != null || volume.getStreamAsset() != null || streaming != null) {
                seen.add(volume);
            }
            bricks |= streaming != null;
        }
        if (seen.isEmpty()) {
            closeGi();
            return;
        }
        WorldSettings world = selectWorld();
        WorldSettings.GiStreaming settings = world == null ? WorldSettings.GiStreaming.DEFAULTS : world.getGiStreaming();
        int atlasMiB = settings.getAtlasMiB();
        if (gi == null || giAtlasMiB != atlasMiB || (bricks && !giBricks)) {
            closeGi();
            io = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "jME GI streaming");
                thread.setDaemon(true);
                return thread;
            });
            long gpu = (long) atlasMiB << 20;
            long total = Math.max(256L << 20, gpu * 4);
            gi = new GiProbeStreamer(io, total, textureLimit, 200, app.getRenderer(),
                    bricks || atlasMiB != 64 ? gpu : 0);
            giAtlasMiB = atlasMiB;
            giBricks = bricks;
            gi.setUnloadMultiplier(1.3);
        }
        gi.setFineStreaming(settings.getPreloadDistance(), settings.getMaxFineProbes());
        Iterator<Map.Entry<GiProbeVolume, Source>> iterator = sources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<GiProbeVolume, Source> entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                gi.unregister(entry.getValue().id);
                iterator.remove();
            }
        }
        for (GiProbeVolume volume : seen) {
            GiProbeData data = volume.getData();
            GiProbeStreamingData streaming = volume.getStreamingData();
            String asset = volume.getStreamAsset();
            Vector3f offset = volume.getWorldTranslation();
            Source old = sources.get(volume);
            if (old != null && old.data == data && old.bricks == streaming && Objects.equals(old.asset, asset)
                    && old.offset.equals(offset)) {
                gi.setAppearance(old.id, volume.getIntensity(), volume.getFadeDistance(), volume.isStabilizedBlending(), volume.isSmoothBlending(), volume.isWideProbeSampling(), volume.isCurvedReceiverCorrection());
                continue;
            }
            if (old != null) {
                gi.unregister(old.id);
            }
            Source source = new Source();
            source.id = "scene-volume-" + sequence++;
            source.data = data;
            source.bricks = streaming;
            source.asset = asset;
            source.offset = offset.clone();
            gi.registerVolume(source.id, volume, app.getAssetManager());
            sources.put(volume, source);
            gi.setAppearance(source.id, volume.getIntensity(), volume.getFadeDistance(), volume.isStabilizedBlending(), volume.isSmoothBlending(), volume.isWideProbeSampling(), volume.isCurvedReceiverCorrection());
        }
        gi.update(app.getCamera().getLocation(), 0, 0, 0);
        for (Material material : catalog.materials) {
            if (supportsGi(material)) {
                gi.addMaterial(material);
                bound.add(material);
            }
        }
    }

    private boolean supportsGi(Material material) {
        for (String name : new String[]{"GiIrradiance", "GiMoments", "GiVolumeData",
                "GiVolumeCount", "GiTextureSize", "GiBias", "GiEnabled", "GiSkyVisibility"}) {
            if (material.getMaterialDef().getMaterialParam(name) == null) {
                return false;
            }
        }
        return true;
    }

    void finishRender() {
        if (gi != null) {
            for (Material material : bound) {
                gi.removeMaterial(material);
            }
        }
        bound.clear();
    }

    private void closeGi() {
        finishRender();
        if (gi != null) {
            gi.close();
            gi = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        sources.clear();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            displayOutput = DisplayOutput.Application;
            worldSettings.close();
            closeGi();
            physics.close();
            catalog.close();
            activeStart = null;
        }
        super.setEnabled(enabled);
    }

    @Override
    public void cleanup() {
        displayOutput = DisplayOutput.Application;
        worldSettings.close();
        closeGi();
        physics.close();
        catalog.close();
        activeStart = null;
        app = null;
        super.cleanup();
    }
}

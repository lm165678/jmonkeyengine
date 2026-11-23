package com.jme3.unity.runtime;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Spatial;
import com.jme3.scene.Spatial.DFSMode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in game-thread dispatcher for one imported subtree. Temporary removal
 * disables a behaviour, and reattachment resumes it without a second Start.
 * The state retains tracked objects until explicit destruction or cleanup.
 * <pre>{@code
 * // 第一步：加载 J3O，挂到游戏的 rootNode。
 * Spatial scene = assetManager.loadModel("Scenes/Imported.j3o");
 * rootNode.attachChild(scene);
 * // 第二步：在游戏线程启用调度。编辑器预览默认不挂此状态。
 * UnityBehaviourState behaviours = new UnityBehaviourState(rootNode);
 * stateManager.attach(behaviours);
 * // 第三步：按需在游戏线程把物理固定步转发给 behaviours.fixedUpdate(seconds)。
 * // 第四步：销毁对象时 behaviours.destroy(object)，临时移除只需 removeFromParent()。
 * }</pre>
 */
public final class UnityBehaviourState extends BaseAppState {
    private static final class Entry {
        final UnityBehaviourControl source;
        final UnityBehaviourControl target;
        boolean present;
        boolean awake;
        boolean active;
        boolean started;
        boolean destroyed;

        Entry(UnityBehaviourControl source, UnityBehaviourControl target) {
            this.source = source;
            this.target = target;
        }
    }

    private final Spatial root;
    private final ClassLoader scriptLoader;
    private final Map<UnityBehaviourControl, Entry> entries = new IdentityHashMap<>();
    private final List<Entry> ordered = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private ClassLoader loader;

    /** Creates a dispatcher scoped to the supplied root, typically SimpleApplication.rootNode. */
    public UnityBehaviourState(Spatial root) {
        this(root, null);
    }

    /** Uses a game-provided script class loader. Null uses the application's class loader. */
    public UnityBehaviourState(Spatial root, ClassLoader scriptLoader) {
        this.root = Objects.requireNonNull(root);
        this.scriptLoader = scriptLoader;
    }

    @Override
    protected void initialize(Application app) {
        loader = scriptLoader == null ? app.getClass().getClassLoader() : scriptLoader;
    }

    @Override
    public void update(float seconds) {
        visible.clear();
        for (Entry entry : ordered) {
            entry.present = false;
        }
        root.depthFirstTraversal(spatial -> {
            for (int i = 0; i < spatial.getNumControls(); i++) {
                if (spatial.getControl(i) instanceof UnityBehaviourControl) {
                    UnityBehaviourControl source = (UnityBehaviourControl) spatial.getControl(i);
                    Entry entry = entries.computeIfAbsent(source, this::create);
                    entry.present = true;
                    visible.add(entry);
                }
            }
        }, DFSMode.PRE_ORDER);
        for (Entry entry : List.copyOf(ordered)) {
            if (entry.active && !eligible(entry)) {
                disable(entry);
            }
        }
        for (Entry entry : visible) {
            if (entry.target != null && !entry.awake && !entry.destroyed && objectActive(entry.source.getSpatial())) {
                entry.awake = true;
                entry.target.onAwake();
            }
        }
        for (Entry entry : visible) {
            if (!entry.active && eligible(entry)) {
                entry.active = true;
                entry.target.onEnable();
            }
        }
        for (Entry entry : visible) {
            if (running(entry) && !entry.started) {
                entry.started = true;
                entry.target.onStart();
            }
        }
        for (Entry entry : visible) {
            if (running(entry)) {
                entry.target.onUpdate(seconds);
            }
        }
        for (Entry entry : visible) {
            if (running(entry)) {
                entry.target.onLateUpdate(seconds);
            }
        }
    }

    private Entry create(UnityBehaviourControl source) {
        UnityBehaviourControl target = source instanceof ImportedBehaviour
                ? ((ImportedBehaviour) source).instantiate(loader) : source;
        Entry entry = new Entry(source, target);
        ordered.add(entry);
        return entry;
    }

    private boolean objectActive(Spatial spatial) {
        for (Spatial current = spatial; current != null; current = current.getParent()) {
            if (Boolean.FALSE.equals(current.getUserData(UnityBehaviourControl.ACTIVE))) {
                return false;
            }
            if (current == root) {
                return true;
            }
        }
        return false;
    }

    private boolean eligible(Entry entry) {
        return entry.target != null && entry.present && !entry.destroyed
                && entry.source.isEnabled() && entry.target.isEnabled()
                && objectActive(entry.source.getSpatial());
    }

    private boolean running(Entry entry) {
        if (entry.active && !eligible(entry)) {
            disable(entry);
        }
        return entry.active;
    }

    /** Dispatches one physics tick on the game thread. No synthetic ticks are generated. */
    public void fixedUpdate(float seconds) {
        if (!isInitialized() || !isEnabled()) {
            return;
        }
        for (Entry entry : List.copyOf(visible)) {
            if (entry.started && running(entry)) {
                entry.target.onFixedUpdate(seconds);
            }
        }
    }

    /** Permanently destroys tracked behaviours in a subtree and detaches it. */
    public void destroy(Spatial object) {
        if (object == root) {
            throw new IllegalArgumentException("Detach the behaviour state to destroy its root behaviours");
        }
        object.depthFirstTraversal(spatial -> {
            for (int i = spatial.getNumControls() - 1; i >= 0; i--) {
                var control = spatial.getControl(i);
                Entry entry = entries.remove(control);
                if (entry != null) {
                    ordered.remove(entry);
                    destroy(entry);
                }
                if (control instanceof UnityBehaviourControl) {
                    spatial.removeControl(control);
                }
            }
        });
        object.removeFromParent();
    }

    private void disable(Entry entry) {
        if (entry.active) {
            entry.active = false;
            entry.target.onDisable();
        }
    }

    private void destroy(Entry entry) {
        entry.destroyed = true;
        disable(entry);
        if (entry.awake) {
            entry.target.onDestroy();
        }
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
        List.copyOf(ordered).forEach(this::disable);
    }

    @Override
    protected void cleanup(Application app) {
        List.copyOf(ordered).forEach(this::destroy);
        entries.clear();
        ordered.clear();
        visible.clear();
        loader = null;
    }
}

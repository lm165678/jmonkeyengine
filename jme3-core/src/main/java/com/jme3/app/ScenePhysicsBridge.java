package com.jme3.app;

import com.jme3.app.state.AppState;
import com.jme3.scene.control.Control;
import java.lang.reflect.Method;
import java.util.*;

/** Optional Bullet/Minie bridge without a core dependency on either backend.
 * Only registrations performed here are removed; application-owned spaces/states
 * and pre-existing registrations are never modified. Runs on application thread.
 */
final class ScenePhysicsBridge implements AutoCloseable {
    private boolean resolved;
    private Class<?> controlType;
    private Class<? extends AppState> stateType;
    private Method getSpace;
    private Method setSpace;
    private Method stateSpace;
    private final Map<Control, Object> registered = new IdentityHashMap<>();
    private SimpleApplication app;
    private AppState owned;

    @SuppressWarnings("unchecked")
    private void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            controlType = Class.forName("com.jme3.bullet.control.PhysicsControl", false, loader);
            Class<?> space = Class.forName("com.jme3.bullet.PhysicsSpace", false, loader);
            stateType = Class.forName("com.jme3.bullet.BulletAppState", false, loader).asSubclass(AppState.class);
            getSpace = controlType.getMethod("getPhysicsSpace");
            setSpace = controlType.getMethod("setPhysicsSpace", space);
            stateSpace = stateType.getMethod("getPhysicsSpace");
        } catch (ClassNotFoundException absent) {
            controlType = null;
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Incompatible Bullet PhysicsControl API", incompatible);
        }
    }

    void update(SimpleApplication application, Collection<Control> controls) {
        app = application;
        resolve();
        if (controlType == null) {
            return;
        }
        Set<Control> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Control control : controls) {
            if (controlType.isInstance(control)) {
                seen.add(control);
            }
        }
        try {
            Iterator<Map.Entry<Control, Object>> iterator = registered.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Control, Object> entry = iterator.next();
                Object current = getSpace.invoke(entry.getKey());
                if (!seen.contains(entry.getKey()) || current != entry.getValue()) {
                    if (current == entry.getValue()) {
                        setSpace.invoke(entry.getKey(), new Object[]{null});
                    }
                    iterator.remove();
                }
            }
            if (seen.isEmpty()) {
                releaseState();
                return;
            }
            boolean needsSpace = false;
            for (Control control : seen) {
                if (getSpace.invoke(control) == null) {
                    needsSpace = true;
                    break;
                }
            }
            if (!needsSpace) {
                return;
            }
            AppState state = app.getStateManager().getState(stateType);
            if (state == null) {
                state = stateType.getDeclaredConstructor().newInstance();
                owned = state;
                app.getStateManager().attach(state);
            }
            // AppStateManager initializes an attached state on the next update.
            // Never manually initialize it or step a second physics simulation.
            if (!state.isInitialized()) {
                return;
            }
            Object space = stateSpace.invoke(state);
            if (space == null) {
                return;
            }
            for (Control control : seen) {
                if (getSpace.invoke(control) == null) {
                    setSpace.invoke(control, space);
                    registered.put(control, space);
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot automatically register scene physics", error);
        }
    }

    private void releaseState() {
        if (owned != null) {
            app.getStateManager().detach(owned);
            owned = null;
        }
    }

    @Override
    public void close() {
        try {
            for (Map.Entry<Control, Object> entry : registered.entrySet()) {
                if (getSpace.invoke(entry.getKey()) == entry.getValue()) {
                    setSpace.invoke(entry.getKey(), new Object[]{null});
                }
            }
            registered.clear();
            releaseState();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot unregister scene physics", error);
        }
    }
}

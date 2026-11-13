package com.jme3.scene;

/**
 * Runtime-only scene mutation notification, delivered synchronously on the scene
 * thread to the changed spatial and its ancestors. Bounds/transforms may still be
 * dirty: enqueue work and consume it after updateGeometricState(), not in callbacks.
 * Listeners are not cloned or serialized. Do not modify the scene from a callback.
 * Direct mutation of returned vectors/buffers bypasses setters; call forceRefresh
 * afterward, as required by normal scene updates. Multiple notifications may coalesce.
 *
 * <pre>{@code
 * // 第一步：在场景根注册，监听整个子树；无需为每个物体创建 Control。
 * SceneChangeListener listener = (spatial, change) -> dirtyObjects.add(spatial);
 * root.addSceneChangeListener(listener);
 * // 第二步：使用 setLocalTranslation/attachChild/setUserData 等正常场景 API。
 * // 第三步：root.updateGeometricState() 后处理队列，此时才能读取最新世界包围盒。
 * // 第四步：服务关闭时解除监听，避免继续持有场景；clone/j3o 不会复制监听器。
 * root.removeSceneChangeListener(listener);
 * }</pre>
 */
@FunctionalInterface
public interface SceneChangeListener {
    enum Change {
        /** Local transform invalidated; descendant world transforms may change. */
        TRANSFORM,
        /** Bounds invalidated; ancestors' aggregate bounds may change. */
        BOUNDS,
        /** User data assigned or removed. */
        USER_DATA,
        /** Subtree attached; parent link is already established. */
        ATTACHED,
        /** Subtree detaching; notification occurs before its old parent link is cleared. */
        DETACHING
    }

    /** @param spatial actual mutation source @param change invalidation category */
    void sceneChanged(Spatial spatial, Change change);
}

package jme3test.sdf;

/**
 * Bounded residency / eviction / explicit material recapture / close example.
 * Run gradlew :jme3-examples:surfaceCacheLifecycle or main; use --smoke for checks.
 * Controls and visible channels match TestSurfaceAttributes.
 * This is a low-level runtime recapture example, not the SDK one-call bake API.
 * For persisted pixels use SurfaceAttributeBaker.bake and TestSurfaceAttributeBake.
 *
 * <pre>{@code
 * // 第一步：容量仅为 2，布局仍可包含任意数量卡片，不一次性分配所有纹理。
 * SurfaceAttributeCache cache = new SurfaceAttributeCache(model, data, revision, 64, 2);
 * // 第二步：按需请求；4、0、1 顺序会淘汰 4，CPU 离线布局仍存在。
 * cache.capture(4, renderManager, viewPort);
 * cache.capture(0, renderManager, viewPort);
 * cache.capture(1, renderManager, viewPort);
 * assert cache.getResident(4) == null;
 * // 第三步：需要 4 时重新请求；不可继续使用旧 Card 的纹理引用。
 * SurfaceAttributeCache.Card card = cache.capture(4, renderManager, viewPort);
 * // 第四步：材质改变时清理消费者并 invalidateAll；光源改变无需重捕获属性。
 * // 第五步：卸载时 close；之后不可再次请求，需要创建新缓存。
 * }</pre>
 */
public final class TestSurfaceCacheLifecycle extends TestSurfaceAttributes {
    public static void main(String[] args) {
        TestSurfaceCacheLifecycle app = new TestSurfaceCacheLifecycle();
        app.lifecycle = true;
        launch(app, args);
    }
}

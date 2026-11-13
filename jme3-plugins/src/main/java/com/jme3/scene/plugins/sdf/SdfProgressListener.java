package com.jme3.scene.plugins.sdf;

/**
 * Called synchronously on the bake thread, never automatically on a UI thread.
 * Return false to cancel; generation throws CancellationException and leaves the
 * model untouched. Thread interruption also cancels without clearing the flag.
 * <pre>{@code
 * // SDK 中通过自己的 UI dispatcher 更新进度；不要在此回调修改活动场景。
 * SdfVolume result = SdfGenerator.generate(snapshot, settings, progress -> {
 *     System.out.println("SDF " + (int) (progress * 100) + "%");
 *     return !cancelRequested.get();
 * });
 * }</pre>
 */
@FunctionalInterface
public interface SdfProgressListener {
    /** @param fraction progress from 0 to 1 @return false to cancel */
    boolean onProgress(float fraction);
}

package com.jme3.scene.plugins.sdf;

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;

/**
 * Independent one-call surface attribute baker; no SDF generation or SDF input.
 * Captures all layout cards internally, reads float data back through Renderer,
 * and releases transient GPU targets. Never attaches partial data or saves a file.
 * Existing six-direction coverage limitations still apply; "all cards" does not
 * imply complete coverage of every concave surface.
 *
 * <pre>{@code
 * // SDK 在渲染线程的视口边界执行；不是放到任意后台线程运行 GPU capture。
 * // 进度回调也在渲染线程；返回 false 或线程中断可在卡片之间取消。
 * SurfaceBakeData surfaces = SurfaceAttributeBaker.bake(
 *         model, revision, renderManager, viewPort, 128, 256L * 1024 * 1024,
 *         progress -> !cancelRequested.get());
 * // 成功后 surfaces.attachTo(model)，再另存 j3o；失败/取消不会替换已有数据。
 * }</pre>
 */
public final class SurfaceAttributeBaker {
    private SurfaceAttributeBaker() {
    }

    /**
     * Bakes every card, bounded to one live capture card plus one readback framebuffer.
     * @param model static source, unchanged for the duration
     * @param revision current external dependency revision
     * @param manager render-thread manager
     * @param restoreView viewport restored on success/cancel/failure
     * @param resolution card edge length 8..2048
     * @param maximumBytes maximum total serialized float payload; peak process memory is larger
     * @param progress optional progress/cancellation callback, synchronous on render thread
     * @return complete CPU-owned attribute pixels, no GPU references
     */
    public static SurfaceBakeData bake(Spatial model, String revision,
            RenderManager manager, ViewPort restoreView, int resolution, long maximumBytes,
            SdfProgressListener progress) {
        report(progress, 0);
        SurfaceBakeData data = SurfaceBakeData.prepare(model, revision);
        data.setTriangleHash(SdfMeshSnapshot.capture(model).geometryHash());
        int count = data.getLayout().getCardCount();
        long required = (long) count * resolution * resolution * 13 * Float.BYTES;
        if (resolution < 8 || resolution > 2048 || maximumBytes <= 0 || required > maximumBytes) {
            throw new IllegalArgumentException("Surface bake exceeds resolution/payload budget: " + required + " bytes");
        }
        report(progress, 0);
        float[][] saved = new float[count][];
        int pixels = resolution * resolution;
        ByteBuffer scratch = BufferUtils.createByteBuffer(pixels * 4 * Float.BYTES);
        Renderer renderer = manager.getRenderer();
        try (SurfaceAttributeCache cache = new SurfaceAttributeCache(model, data, revision, resolution, 1)) {
            for (int cardIndex = 0; cardIndex < count; cardIndex++) {
                SdfMeshSnapshot.cancelled();
                SurfaceAttributeCache.Card card = cache.capture(cardIndex, manager, restoreView);
                float[] values = new float[pixels * 13];
                // Separate read framebuffer selects one color attachment without raw GL calls.
                for (SurfaceAttributeCache.Channel channel : SurfaceAttributeCache.Channel.values()) {
                    FrameBuffer read = new FrameBuffer(resolution, resolution, 1);
                    try {
                        read.setDepthTexture(card.getDepthTexture());
                        read.setColorTexture(card.getTexture(channel));
                        scratch.clear();
                        renderer.readFrameBufferWithFormat(read, scratch, Image.Format.RGBA32F);
                        scratch.asFloatBuffer().get(values, channel.ordinal() * pixels * 4, pixels * 4);
                        if (channel == SurfaceAttributeCache.Channel.DIFFUSE) {
                            scratch.clear();
                            renderer.readFrameBufferWithFormat(read, scratch, Image.Format.Depth32F);
                            scratch.asFloatBuffer().get(values, pixels * 12, pixels);
                        }
                    } finally {
                        renderer.setFrameBuffer(restoreView.getOutputFrameBuffer());
                        read.dispose();
                    }
                }
                saved[cardIndex] = values;
                report(progress, (cardIndex + 1f) / count);
            }
        } finally {
            renderer.setFrameBuffer(restoreView.getOutputFrameBuffer());
            // Force viewport restoration after readFrameBuffer changed renderer viewport size.
            // setCamera can otherwise skip a camera whose viewport flag has not changed.
            restoreView.getCamera().setViewPort(restoreView.getCamera().getViewPortLeft(),
                    restoreView.getCamera().getViewPortRight(), restoreView.getCamera().getViewPortBottom(),
                    restoreView.getCamera().getViewPortTop());
            manager.setCamera(restoreView.getCamera(), false,
                    restoreView.getRenderTargetWidth(), restoreView.getRenderTargetHeight());
            renderer.setBackgroundColor(restoreView.getBackgroundColor());
        }
        data.complete(new SurfaceAttributeData(resolution, saved));
        return data;
    }

    /**
     * One-call SDK entry with a 256 MiB uncompressed attribute payload budget.
     * Creates layout/mapping and every attribute/depth card, never an SDF.
     * <pre>{@code
     * // 在有 OpenGL 上下文的渲染线程视口边界，一次调用完成全部方向捕获。
     * SurfaceBakeData surfaces = SurfaceAttributeBaker.bake(
     *         model, dependencyRevision, renderManager, viewPort, 128, progress);
     * surfaces.attachTo(model);
     * // 保存 model 后加载时可 surfaces.getAttributes().uploadAll()，不再 capture。
     * }</pre>
     * @param model static source
     * @param revision external shader/material/texture content revision
     * @param manager render-thread manager
     * @param restoreView viewport to restore
     * @param resolution pixel edge length per card
     * @param progress optional progress/cancellation callback
     * @return complete independent surface bake, not attached to model yet
     */
    public static SurfaceBakeData bake(Spatial model, String revision, RenderManager manager,
            ViewPort restoreView, int resolution, SdfProgressListener progress) {
        return bake(model, revision, manager, restoreView, resolution, 256L * 1024 * 1024, progress);
    }
    private static void report(SdfProgressListener progress, float fraction) {
        SdfMeshSnapshot.cancelled();
        if (progress != null && !progress.onProgress(fraction)) {
            throw new CancellationException("Surface attribute bake cancelled");
        }
    }
}

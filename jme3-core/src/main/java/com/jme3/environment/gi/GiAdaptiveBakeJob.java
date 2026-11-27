package com.jme3.environment.gi;

import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Transactional coarse plus adaptive-brick bake, using normal streaming assets.
 * All operations except writing exported immutable results run on the render
 * thread before drawing. Keep the capture scene fixed, as with GiBakeJob. A
 * private staging hierarchy keeps all previous data live until every brick is
 * ready. Cancellation/failure discards staging; success replaces only generated
 * refinements, preserving user-authored child nodes. A zero-refinement layout
 * restores a coarse-only bake. Surface-layout targets instead bake one free-position
 * volume and ignore regular refinement budgets; setSurfaceLayout(false) restores
 * the lattice/refinement path on the next bake. Old data can still be shared by bindings and is
 * not disposed here. Unload old bindings before disposing their resources.
 *
 * <pre>{@code
 * // 第一步：固定捕获场景并更新世界变换。
 * rootNode.updateGeometricState();
 * // 第二步：启动；最多 4 个局部加密块是默认值，另保留原粗网格。
 * GiAdaptiveBakeJob job = GiAdaptiveBakeJob.start(renderManager, rootNode, volume,
 *         new GiSceneBaker.Settings(), new GiAdaptiveProbeLayout.Settings(),
 *         view -> { }, () -> false, completed -> { });
 * // 第三步：在 AppState.update 每帧推进，预算是软上限。
 * if (job.step(2_000_000L)) {
 *     // 第四步：保存包含子 Volume 的场景，或逐个导出不可变 .giprobe 并发布引用。
 *     for (GiProbeVolume baked : job.getPublishedVolumes()) {
 *         GiProbeData payload = baked.getData();
 *     }
 *     job.close();
 * }
 * // 取消调用 close；SimpleApplication 自动加载这些普通 Volume，无需额外管理器。
 * }</pre>
 * Requires the same GPU capabilities as GiSceneBaker. maxWorkingBytes also caps
 * the aggregate staged payload estimate; capture work is still per volume.
 * If sky occlusion is enabled, all volumes share one depth atlas within the
 * remaining budget. The job releases it before publication or on cancellation.
 * Adaptive baking always enables classification, and planning uses the same
 * validator and effective clearance as capture. maxRefinements=0 restores the
 * caller's ordinary classification choice. Multi-bounce capture currently requires
 * maxRefinements=0: separate bricks do not share a previous-pass irradiance field.
 * Set quality.bounceCount=1 to restore adaptive baking. Progress counts completed
 * probe-pass work units, including all requested diffuse passes.
 */
public final class GiAdaptiveBakeJob implements AutoCloseable {
    private final RenderManager manager;
    private final Spatial scene;
    private final GiProbeVolume target;
    private final GiProbeVolume staging;
    private final Transform transform;
    private final Vector3f coverage;
    private final Vector3f counts;
    private final GiSceneBaker.Settings quality;
    private final Consumer<ViewPort> configure;
    private final BooleanSupplier cancelled;
    private final IntConsumer progress;
    private final GiCaptureSky captureSky;
    private final List<GiProbeVolume> pending = new ArrayList<>();
    private List<GiProbeVolume> published;
    private GiBakeJob current;
    private final GiAdaptiveProbeLayout.Plan layoutPlan;
    private int index;
    private int completed;
    private boolean closed;

    /** Creates a staged job; planning is synchronous and does not mutate target. */
    public static GiAdaptiveBakeJob start(RenderManager manager, Spatial scene, GiProbeVolume target,
            GiSceneBaker.Settings quality, GiAdaptiveProbeLayout.Settings layout,
            Consumer<ViewPort> configure, BooleanSupplier cancelled, IntConsumer progress) {
        return new GiAdaptiveBakeJob(manager, scene, target, quality, layout.copy(), configure, cancelled, progress);
    }

    private GiAdaptiveBakeJob(RenderManager manager, Spatial scene, GiProbeVolume target,
            GiSceneBaker.Settings quality, GiAdaptiveProbeLayout.Settings layout,
            Consumer<ViewPort> configure, BooleanSupplier cancelled, IntConsumer progress) {
        this.manager = manager;
        this.scene = scene;
        this.target = target;
        this.quality = quality.copy();
        this.configure = configure;
        this.cancelled = cancelled;
        this.progress = progress;
        transform = target.getWorldTransform().clone();
        coverage = target.getCoverage();
        counts = target.getProbeCounts();
        staging = new GiProbeVolume();
        staging.configure(coverage, (int) counts.x, (int) counts.y, (int) counts.z);
        staging.setLocalTransform(transform);
        staging.setSurfaceLayout(target.isSurfaceLayout());
        if (target.isSurfaceLayout()) {
            layout.maxRefinements = 0;
        }
        if (quality.bounceCount > 1 && layout.maxRefinements > 0) {
            throw new IllegalArgumentException("Multi-bounce baking requires maxRefinements=0");
        }
        staging.setIntensity(target.getIntensity());
        pending.add(staging);
        // Adaptive coverage is meaningful only if capture uses the same safe
        // classification policy as planning. Ordinary non-adaptive jobs retain
        // the caller's classifyProbes choice.
        if (layout.maxRefinements > 0) {
            this.quality.classifyProbes = true;
        }
        if (this.quality.classifyProbes && this.quality.validator == null) {
            this.quality.validator = GiProbePlacement.validator(scene);
        }
        layout.surfaceClearance = Math.max(this.quality.probeClearance, this.quality.near * 1.25f);
        layout.relativeClearance = this.quality.relativeProbeClearance;
        layoutPlan = GiAdaptiveProbeLayout.planDetailed(scene, target, layout, this.quality.validator);
        for (GiProbeVolume refinement : layoutPlan.getVolumes()) {
            staging.attachChild(refinement);
            pending.add(refinement);
        }
        staging.updateGeometricState();
        long probes = 0;
        for (GiProbeVolume volume : pending) {
            probes += volume.getGrid().getProbeCount();
        }
        long estimate = probes * (768L + 32L * quality.visibilityResolution * quality.visibilityResolution)
                + 6L * quality.captureSize * quality.captureSize * 20;
        if (quality.bounceCount > 1) {
            estimate *= 2;
        }
        if (estimate > quality.maxWorkingBytes) {
            throw new IllegalArgumentException("Adaptive bake staging exceeds working memory budget");
        }
        // The snapshot and world-space sky visibility are shared by every brick.
        // Reserve the atlas after accounting for all retained brick output.
        captureSky = this.quality.captureSkyOcclusion
                ? GiCaptureSky.create(manager, scene, quality.maxWorkingBytes - estimate) : null;
        this.quality.captureSkyOcclusion = false;
    }

    /** Processes one brick within the soft budget and publishes the batch atomically. */
    public boolean step(long budgetNanos) {
        if (published != null) {
            return true;
        }
        if (closed || budgetNanos <= 0) {
            throw new IllegalStateException("Open adaptive job and positive budget required");
        }
        try {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Adaptive GI bake cancelled");
            }
            if (staging.isSurfaceLayout() != target.isSurfaceLayout()
                    || !transform.equals(target.getWorldTransform()) || !coverage.equals(target.getCoverage())
                    || !counts.equals(target.getProbeCounts())) {
                throw new IllegalStateException("Volume changed during adaptive bake");
            }
            if (current == null) {
                int baseline = completed;
                current = GiSceneBaker.start(manager, scene, pending.get(index), quality, configure,
                        cancelled, count -> progress.accept(baseline + count), captureSky);
            }
            if (!current.step(budgetNanos)) {
                return false;
            }
            completed += current.getCompletedProbeCount();
            current.close();
            current = null;
            index++;
            if (index < pending.size()) {
                return false;
            }
            if (captureSky != null) {
                captureSky.close();
            }
            // Nothing is exposed until every result is complete. No GPU calls
            // or callbacks intervene during this scene-thread commit.
            target.publish(staging.getData());
            target.setUserData("jme.gi.adaptive.surfaceTargets", layoutPlan.getSurfaceTargets());
            target.setUserData("jme.gi.adaptive.coveredTargets", layoutPlan.getCoveredTargets());
            for (Spatial child : new ArrayList<>(target.getChildren())) {
                if (Boolean.TRUE.equals(child.getUserData(GiAdaptiveProbeLayout.GENERATED))) {
                    child.removeFromParent();
                }
            }
            List<GiProbeVolume> ready = new ArrayList<>();
            ready.add(target);
            for (int i = 1; i < pending.size(); i++) {
                GiProbeVolume refinement = pending.get(i);
                refinement.setIntensity(target.getIntensity());
                refinement.setStabilizedBlending(target.isStabilizedBlending());
                refinement.setWideProbeSampling(target.isWideProbeSampling());
                refinement.setCurvedReceiverCorrection(target.isCurvedReceiverCorrection());
                refinement.setSmoothBlending(target.isSmoothBlending());
                target.attachChild(refinement);
                ready.add(refinement);
            }
            published = Collections.unmodifiableList(ready);
            closed = true;
            return true;
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    /** Finite surface-sample diagnostics, available after construction.
     * Uncovered targets are not a successful complete refinement. Increase the
     * brick budget or split the volume; the coarse grid remains the fallback.
     * <pre>{@code
     * // 第一步：start 后检查有限采样的覆盖缺口，不把块数当作质量保证。
     * GiAdaptiveProbeLayout.Plan plan = job.getLayoutPlan();
     * System.out.println(plan.getCoveredTargets() + "/" + plan.getSurfaceTargets());
     * // 第二步：取消可 close；成功后正常保存，运行时无需再次规划。
     * }</pre>
     */
    public GiAdaptiveProbeLayout.Plan getLayoutPlan() {
        return layoutPlan;
    }

    /** Completed target and generated children; save each external reference before saving J3O. */
    public List<GiProbeVolume> getPublishedVolumes() {
        if (published == null) {
            throw new IllegalStateException("Adaptive bake has not completed");
        }
        return published;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (current != null) {
                current.close();
            }
            for (GiProbeVolume volume : pending) {
                GiProbeData data = volume.getData();
                if (data != null) {
                    data.dispose();
                }
            }
        } finally {
            if (captureSky != null) {
                captureSky.close();
            }
        }
    }
}

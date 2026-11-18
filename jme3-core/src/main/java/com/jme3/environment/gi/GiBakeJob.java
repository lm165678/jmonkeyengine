package com.jme3.environment.gi;

import com.jme3.material.MatParamOverride;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.math.Vector3f;
import com.jme3.math.Quaternion;
import com.jme3.renderer.*;
import com.jme3.scene.Spatial;
import com.jme3.shader.VarType;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import java.io.*;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import java.util.zip.*;

/**
 * Incremental render-thread bake. Each step restores render state. The time budget
 * is soft: one cubemap face/readback, SH projection, visibility row, placement test
 * or final atlas publication is indivisible. Keep the capture scene stable; cancel
 * and restart after edits. Old volume data remains live until successful completion.
 * Settings are copied when the job starts. A custom validator may use a signed SDF.
 *
 * <pre>{@code
 * // 第一步：创建任务。版本号必须包含场景、光照及自定义有效性检测配置。
 * quality.sceneRevision = "room-v4";
 * GiBakeJob job = GiSceneBaker.start(renderManager, rootNode, volume, quality,
 *         capture -> { }, () -> false, completed -> { });
 * // 第二步：每帧绘制之前推进约 2ms；下一帧继续，不阻塞等待整个体积。
 * boolean done = job.step(2_000_000L);
 * // 第三步：可在 step 之间保存断点；只保存完成的探针。
 * // job.saveCheckpoint(outputStream);
 * // 新建相同版本的任务后，job.restoreCheckpoint(inputStream) 再继续 step。
 * // 第四步：完成后绑定新资源；取消时 close，旧资源保持可用。
 * if (done) {
 *     GiProbeData result = job.getResult();
 *     job.close();
 * }
 * }</pre>
 * Checkpoint IO is synchronous; SDK callers should stage bytes here and write files
 * on a worker. invalidate() supports targeted rebaking before publication; callers
 * must include every probe whose captured radiance could change, not only probes
 * inside edited geometry. No automatic dependency analysis is claimed.
 * With bounceCount greater than one, publication waits for all passes and
 * progress counts probe-pass work units. invalidate restarts every pass because
 * indirect changes propagate beyond the selected probes. Checkpoint methods
 * explicitly reject multi-bounce jobs; the single-bounce format is unchanged.
 * <pre>{@code
 * // 第一步：多次反射需显式设置；默认仍为一次。
 * quality.bounceCount = 3;
 * // 第二步：使用上面的 start/step 流程；不要在反射轮次之间发布中间结果。
 * // 第三步：多次反射任务取消后从头重启；一次反射仍支持原断点恢复流程。
 * }</pre>
 */
public final class GiBakeJob implements AutoCloseable {
    private final RenderManager manager;
    private final Spatial scene;
    private final Spatial skyStateRoot;
    private final String skyState;
    private final GiProbeVolume volume;
    private final GiSceneBaker.Settings settings;
    private final GiProbeGrid grid;
    private final Vector3f[] surfacePositions;
    private final Vector3f worldOffset;
    private final Vector3f worldScale;
    private final GiSceneBaker.Capture capture;
    private final GiSceneBaker.ProbeValidator placement;
    private final BooleanSupplier cancelled;
    private final IntConsumer progress;
    private final float[][] sh;
    private final float[][] skyVisibility;
    private final float[][] moments;
    private final Vector3f[] offsets;
    private final boolean[] valid;
    private final BitSet completed = new BitSet();
    private final Vector3f position = new Vector3f();
    private int probe;
    private int stage;
    private boolean closed;
    private GiProbeData result;
    private GiProbeData previousBounce;
    private int bounce;
    private final Set<Material> bounceMaterials = Collections.newSetFromMap(new IdentityHashMap<>());

    GiBakeJob(RenderManager manager, Spatial scene, GiProbeVolume volume, GiSceneBaker.Settings settings,
            Consumer<ViewPort> configure, BooleanSupplier cancelled, IntConsumer progress) {
        this.manager = manager;
        this.scene = scene;
        Spatial root = scene;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        skyStateRoot = root;
        skyState = GiSkyState.capture(root);
        this.volume = volume;
        this.settings = settings;
        this.cancelled = cancelled;
        this.progress = progress;
        if (settings.bounceCount > 1) {
            scene.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry) {
                    Material material = ((Geometry) spatial).getMaterial();
                    if (material.getMaterialDef().getMaterialParam("GiEnabled") != null) {
                        if (material.getMaterialDef().getMaterialParam("GiBakeBounce") == null) {
                            throw new IllegalArgumentException("Multi-bounce GI requires GiBakeBounce support: "
                                    + material.getMaterialDef().getName());
                        }
                        bounceMaterials.add(material);
                    }
                }
            });
        }
        grid = volume.getBakeGrid();
        worldOffset = volume.getWorldTranslation().clone();
        worldScale = volume.getWorldScale().clone();
        sh = new float[grid.getProbeCount()][];
        skyVisibility = settings.separateSky ? new float[grid.getProbeCount()][] : null;
        moments = new float[grid.getProbeCount()][];
        offsets = new Vector3f[grid.getProbeCount()];
        valid = new boolean[grid.getProbeCount()];
        placement = !settings.classifyProbes ? null : settings.validator != null
                ? settings.validator : GiProbePlacement.validator(scene);
        surfacePositions = volume.isSurfaceLayout()
                ? GiSurfaceProbeLayout.plan(scene, grid, worldOffset,
                        Math.max(settings.probeClearance, settings.near * 1.25f), settings.relativeProbeClearance)
                : null;
        capture = new GiSceneBaker.Capture(settings, scene);
        try {
            GiCaptureShadows.configure(manager, scene, capture.viewport, settings);
            configure.accept(capture.viewport);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    /** Advances at least one work unit; returns true only after atomic publication. */
    public boolean step(long budgetNanos) {
        if (result != null) {
            return true;
        }
        if (closed || budgetNanos <= 0) {
            throw new IllegalStateException("Open job and positive frame budget required");
        }
        long start = System.nanoTime();
        try {
            if (!skyState.equals(GiSkyState.capture(skyStateRoot))) {
                throw new IllegalStateException("Sky changed during GI bake; restart the bake");
            }
            do {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("GI bake cancelled");
                }
                if ((surfacePositions != null) != volume.isSurfaceLayout()
                        || !worldOffset.equals(volume.getWorldTranslation())
                        || !Quaternion.IDENTITY.equals(volume.getWorldRotation())
                        || !worldScale.equals(volume.getWorldScale())
                        || !grid.getSpacing().equals(volume.getBakeGrid().getSpacing())
                        || !grid.getCounts().equals(volume.getGrid().getCounts())) {
                    throw new IllegalStateException("Volume changed during bake");
                }
                if (probe >= grid.getProbeCount()) {
                    GiProbeData ready = new GiProbeData(grid, settings.visibilityResolution, sh, moments,
                            offsets, valid, settings.compactStorage && surfacePositions == null, settings.far, skyVisibility);
                    if (surfacePositions != null) {
                        ready.enableSurfaceLayout();
                    }
                    if (bounce + 1 < settings.bounceCount) {
                        if (previousBounce != null) {
                            previousBounce.dispose();
                        }
                        previousBounce = ready;
                        bounce++;
                        completed.clear();
                        probe = 0;
                        stage = 0;
                        continue;
                    }
                    close();
                    volume.publish(ready, skyState);
                    result = ready;
                    return true;
                }
                unit();
            } while (System.nanoTime() - start < budgetNanos);
            return false;
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private void unit() {
        if (stage == 0 && bounce > 0) {
            position.set(grid.position(probe, new Vector3f()).addLocal(worldOffset).addLocal(offsets[probe]));
            if (!valid[probe]) {
                finishProbe();
                return;
            }
        } else if (stage == 0) {
            Vector3f base = grid.position(probe, new Vector3f()).addLocal(worldOffset);
            position.set(base);
            // A valid probe must not capture a nearby wall inside the camera's
            // near plane. User clearance is a minimum, not permission to clip.
            float clearance = Math.max(settings.probeClearance, settings.near * 1.25f);
            Vector3f step = grid.getSpacing();
            clearance = Math.max(clearance, Math.min(step.x, Math.min(step.y, step.z)) * settings.relativeProbeClearance);
            if (surfacePositions != null) {
                valid[probe] = surfacePositions[probe] != null;
                if (valid[probe]) {
                    position.set(surfacePositions[probe]);
                }
            } else {
                valid[probe] = placement == null
                        || placement.place(scene, base, grid.getSpacing(), clearance, position);
            }
            Vector3f offset = position.subtract(base);
            Vector3f spacing = grid.getSpacing();
            if (!Vector3f.isValidVector(position) || (surfacePositions == null && (Math.abs(offset.x) > spacing.x * 0.45f
                    || Math.abs(offset.y) > spacing.y * 0.45f || Math.abs(offset.z) > spacing.z * 0.45f))) {
                valid[probe] = false;
                offset.set(0, 0, 0);
            }
            offsets[probe] = offset;
            moments[probe] = new float[settings.visibilityResolution * settings.visibilityResolution * 2];
            if (!valid[probe]) {
                sh[probe] = new float[27];
                if (skyVisibility != null) {
                    skyVisibility[probe] = new float[27];
                }
                finishProbe();
                return;
            }
        }
        if (stage < 6) {
            Camera previousCamera = manager.getCurrentCamera();
            FrameBuffer previousBuffer = manager.getRenderer().getCurrentFrameBuffer();
            MatParamOverride enabled = new MatParamOverride(VarType.Boolean, "GiEnabled", previousBounce != null);
            MatParamOverride captureBounce = new MatParamOverride(VarType.Boolean, "GiBakeBounce", previousBounce != null);
            // Runtime edge fading must not attenuate transport between bake passes.
            MatParamOverride fade = new MatParamOverride(VarType.Float, "GiFadeDistance", 0f);
            List<GiProbeData.Binding> bindings = new ArrayList<>();
            try {
                if (previousBounce != null) {
                    for (Material material : bounceMaterials) {
                        bindings.add(previousBounce.bind(material, worldOffset, 0.05f));
                    }
                }
                manager.addForcedMatParam(enabled);
                manager.addForcedMatParam(captureBounce);
                if (previousBounce != null) {
                    manager.addForcedMatParam(fade);
                }
                capture.renderFace(manager, position, stage);
            } finally {
                manager.removeForcedMatParam(fade);
                manager.removeForcedMatParam(captureBounce);
                manager.removeForcedMatParam(enabled);
                for (int i = bindings.size() - 1; i >= 0; i--) {
                    bindings.get(i).close();
                }
                manager.getRenderer().setFrameBuffer(previousBuffer);
                if (previousCamera != null) {
                    manager.setCamera(previousCamera, false);
                }
            }
        } else if (stage == 6) {
            sh[probe] = GiProbeBaker.project(position, settings.radianceSamples,
                    (p, direction, color) -> capture.color(direction, color), cancelled);
            if (skyVisibility != null && bounce == 0) {
                skyVisibility[probe] = GiProbeBaker.project(position, settings.radianceSamples,
                        (p, direction, color) -> {
                            float visibility = capture.skyVisibility(direction);
                            color.set(visibility, visibility, visibility);
                        }, cancelled);
            }
            if (bounce > 0) {
                finishProbe();
                return;
            }
        } else {
            capture.momentsRow(stage - 7, moments[probe]);
        }
        stage++;
        if (stage == 7 + settings.visibilityResolution) {
            finishProbe();
        }
    }

    private void finishProbe() {
        completed.set(probe);
        stage = 0;
        progress.accept(getCompletedProbeCount());
        probe = completed.nextClearBit(0);
    }

    /** Completed probe-pass work units; includes previous complete bounce passes. */
    public int getCompletedProbeCount() {
        return bounce * grid.getProbeCount() + completed.cardinality();
    }

    public GiProbeData getResult() {
        if (result == null) {
            throw new IllegalStateException("Bake has not completed");
        }
        return result;
    }

    /** Invalidates selected probes before completion, retaining the remaining completed probes. */
    public void invalidate(BitSet probes) {
        if (closed || probes.length() > grid.getProbeCount()) {
            throw new IllegalStateException("Invalid probe selection or closed job");
        }
        if (settings.bounceCount > 1) {
            // Changed incoming radiance can affect every probe in later passes.
            completed.clear();
            bounce = 0;
            if (previousBounce != null) {
                previousBounce.dispose();
                previousBounce = null;
            }
        } else {
            completed.andNot(probes);
        }
        stage = 0;
        probe = completed.nextClearBit(0);
    }

    private String identity() {
        if (settings.sceneRevision.isEmpty()) {
            throw new IllegalStateException("Set sceneRevision for checkpoint use");
        }
        return "sky-separated-v2:" + skyState + ":" + volume.isSurfaceLayout() + ":" + settings.sceneRevision + ":" + worldOffset + ":" + worldScale + ":" + grid.getCounts() + ":" + grid.getSpacing()
                + ":" + settings.captureSize + ":" + settings.radianceSamples + ":" + settings.visibilityResolution
                + ":" + settings.visibilitySamples + ":" + settings.near + ":" + settings.far
                + ":" + settings.visibilityCone + ":" + settings.probeClearance + ":" + settings.relativeProbeClearance + ":" + settings.classifyProbes
                + ":" + settings.separateSky + ":" + settings.background + ":" + settings.captureShadows + ":" + settings.shadowMapSize;
    }

    /** Saves complete probes only; does not close the caller's output.
     * @throws UnsupportedOperationException if bounceCount is greater than one
     */
    public void saveCheckpoint(OutputStream output) throws IOException {
        requireSingleBounceCheckpoint();
        String identity = identity();
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        DataOutputStream out = new DataOutputStream(gzip);
        out.writeInt(0x47494233);
        out.writeUTF(identity);
        out.writeInt(grid.getProbeCount());
        for (int p = 0; p < grid.getProbeCount(); p++) {
            out.writeBoolean(completed.get(p));
            if (completed.get(p)) {
                out.writeBoolean(valid[p]);
                out.writeFloat(offsets[p].x);
                out.writeFloat(offsets[p].y);
                out.writeFloat(offsets[p].z);
                for (float value : sh[p]) {
                    out.writeFloat(value);
                }
                if (skyVisibility != null) {
                    for (float value : skyVisibility[p]) {
                        out.writeFloat(value);
                    }
                }
                for (float value : moments[p]) {
                    out.writeFloat(value);
                }
            }
        }
        out.flush();
        gzip.finish();
    }

    /** Restores matching data into a fresh job. sceneRevision/settings must match exactly.
     * @throws UnsupportedOperationException if bounceCount is greater than one
     */
    public void restoreCheckpoint(InputStream input) throws IOException {
        requireSingleBounceCheckpoint();
        if (closed || stage != 0 || !completed.isEmpty()) {
            throw new IllegalStateException("Restore into a fresh job");
        }
        DataInputStream in = new DataInputStream(new GZIPInputStream(input));
        if (in.readInt() != 0x47494233 || !in.readUTF().equals(identity()) || in.readInt() != grid.getProbeCount()) {
            throw new IOException("Checkpoint scene or capture settings do not match");
        }
        try {
            for (int p = 0; p < grid.getProbeCount(); p++) {
                if (!in.readBoolean()) {
                    continue;
                }
                valid[p] = in.readBoolean();
                offsets[p] = new Vector3f(readFinite(in), readFinite(in), readFinite(in));
                Vector3f spacing = grid.getSpacing();
                if (surfacePositions == null && (Math.abs(offsets[p].x) > spacing.x * 0.45f || Math.abs(offsets[p].y) > spacing.y * 0.45f
                        || Math.abs(offsets[p].z) > spacing.z * 0.45f)) {
                    throw new IOException("Checkpoint relocation exceeds cell bounds");
                }
                sh[p] = new float[27];
                moments[p] = new float[settings.visibilityResolution * settings.visibilityResolution * 2];
                for (int c = 0; c < 27; c++) {
                    sh[p][c] = readFinite(in);
                }
                if (skyVisibility != null) {
                    skyVisibility[p] = new float[27];
                    for (int c = 0; c < 27; c++) {
                        skyVisibility[p][c] = readFinite(in);
                    }
                }
                for (int c = 0; c < moments[p].length; c++) {
                    moments[p][c] = readFinite(in);
                }
                completed.set(p);
            }
            probe = completed.nextClearBit(0);
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    private static float readFinite(DataInputStream in) throws IOException {
        float value = in.readFloat();
        if (!Float.isFinite(value)) {
            throw new IOException("Non-finite checkpoint sample");
        }
        return value;
    }

    private void requireSingleBounceCheckpoint() {
        if (settings.bounceCount != 1) {
            throw new UnsupportedOperationException("Checkpoints currently require bounceCount=1");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (previousBounce != null) {
            previousBounce.dispose();
            previousBounce = null;
        }
        try {
            for (com.jme3.post.SceneProcessor processor : capture.viewport.getProcessors()) {
                processor.cleanup();
            }
        } finally {
            manager.releaseShadowManager(capture.viewport);
            manager.getRenderer().deleteFrameBuffer(capture.framebuffer);
            for (int face = 0; face < 6; face++) {
                BufferUtils.destroyDirectBuffer(capture.colors[face]);
                BufferUtils.destroyDirectBuffer(capture.depths[face]);
            }
        }
    }
}

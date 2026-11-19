package com.jme3.environment.gi;

import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import java.util.*;
import java.util.concurrent.*;

/**
 * <p>SimpleApplication automatically manages attached GiProbeVolume nodes through
 * SceneServicesState. For explicit low-level bindings/streaming as below, call
 * {@code app.getSceneServices().setEnabled(false)} before installing manual
 * bindings. Close manual bindings before enabling automatic services again.
 * Standalone data not attached as a volume is unaffected.</p>
 *

 * Camera-driven asynchronous chunk residency with a bounded active set. Workers
 * only load CPU data; update(), binding changes and disposal run on the render
 * thread before drawing. The caller owns the executor. At most two loads run at
 * once. Failed chunks are not retried until retry() is called. Leaving coverage
 * evicts chunks; unavailable chunks contribute no GI while neighbors remain usable.
 *
 * <p>Budget includes conservative reservations for source data, CPU/GPU atlases
 * and replacement overlap. Driver/bindless texture-cache copies are external to
 * this budget. Loaders must honor their declared uncompressed byte bound; assets
 * exceeding it are rejected. The active set is capped at 16 volumes; use coarser
 * distant volumes rather than attempting to keep every world probe resident.
 *
 * <pre>{@code
 * // 第一步：登记分块目录；IO 只在加载器中执行，不在 update 中读取文件。
 * ExecutorService io = Executors.newFixedThreadPool(2);
 * GiProbeStreamer stream = new GiProbeStreamer(io, 128L * 1024 * 1024, 4096, 200, renderer);
 * stream.register(new GiProbeStreamer.Chunk("chunk-0", 0, 0, 0,
 *         new Vector3f(64, 32, 64), 4L * 1024 * 1024, () -> {
 *             try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
 *                 return GiProbeData.loadCompressed(in);
 *             }
 *         }));
 * // 第二步：登记接收材质；跨块的大物体仍可共享同一材质。
 * stream.addMaterial(material);
 * // 第三步：每帧更新；原点重定位后传入新的双精度世界原点。
 * stream.update(cam.getLocation(), 0.0, 0.0, 0.0);
 * // 第四步：卸载场景，恢复原材质；线程池由调用方关闭。
 * stream.close();
 * io.shutdown();
 * }</pre>
 * The renderer-backed constructor keeps stable pages and uploads only new data.
 * The renderer-independent compatibility constructor rebuilds snapshot atlases.
 * Updates are not a hard real-time guarantee: initial GPU allocation and a single
 * driver call may exceed the soft budget. Inspect getResidentBytes(), getAtlasBytes()
 * and getPageAdmissionFailures(). Mixed resolutions share full-float strip pages;
 * contiguous-space fragmentation can reject a chunk without moving its neighbors.
 * Unregister/register replaces a baked revision without exposing partial data;
 * GI is temporarily unavailable for that chunk until its replacement is uploaded.
 * The chunk directory uses a double-precision spatial index. Register/unregister
 * invalidates it; the next update rebuilds it once. Camera movement queries only
 * nearby directory branches, instead of sorting the entire world every frame.
 */
public final class GiProbeStreamer implements AutoCloseable {
    /** Retains ownership even when an IO provider ignores interruption. */
    private static final class Load extends FutureTask<GiProbeData> {
        Load(Callable<GiProbeData> loader) {
            super(() -> Objects.requireNonNull(loader.call(), "GI loader returned null"));
        }

        @Override
        protected synchronized void set(GiProbeData data) {
            if (isCancelled()) {
                data.dispose();
            } else {
                super.set(data);
            }
        }

        synchronized void discard() {
            if (isDone() && !isCancelled()) {
                try {
                    get().dispose();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException exception) {
                    // Failed loads own no returned data.
                }
            } else {
                cancel(true);
            }
        }
    }
    /** Metadata-only directory entry. x/y/z are double-precision absolute chunk offsets. */
    public static final class Chunk {
        final String id;
        final double x;
        final double y;
        final double z;
        final Vector3f size;
        final long bytes;
        final Callable<GiProbeData> loader;
        boolean borrowed;
        float intensity = 1;
        float fadeDistance = -1;
        boolean stabilized;
        boolean wide;
        boolean curved;
        boolean smooth;

        public Chunk(String id, double x, double y, double z, Vector3f coverage,
                long maximumTextureBytes, Callable<GiProbeData> loader) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Vector3f.isValidVector(coverage) || coverage.x <= 0 || coverage.y <= 0 || coverage.z <= 0
                    || maximumTextureBytes <= 0) {
                throw new IllegalArgumentException("Finite bounds and a positive payload bound required");
            }
            this.id = Objects.requireNonNull(id);
            this.x = x;
            this.y = y;
            this.z = z;
            size = coverage.clone();
            bytes = maximumTextureBytes;
            this.loader = Objects.requireNonNull(loader);
        }

        /** Registers already-loaded editor data without taking ownership of it. */
        public static Chunk borrowed(String id, double x, double y, double z,
                Vector3f coverage, GiProbeData data) {
            Chunk chunk = new Chunk(id, x, y, z, coverage, data.getTextureBytes(), () -> data);
            chunk.borrowed = true;
            return chunk;
        }

        private double distance(double cx, double cy, double cz) {
            double dx = Math.max(Math.max(x - cx, 0), cx - x - size.x);
            double dy = Math.max(Math.max(y - cy, 0), cy - y - size.y);
            double dz = Math.max(Math.max(z - cz, 0), cz - z - size.z);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private final ExecutorService executor;
    private final long budget;
    private final int textureLimit;
    private final double distance;
    private final Map<String, Chunk> directory = new LinkedHashMap<>();
    private final Map<Chunk, Load> pending = new LinkedHashMap<>();
    private final Map<Chunk, GiProbeData> resident = new LinkedHashMap<>();

    /** Borrows resident CPU data for diagnostics on the render thread only.
     * Returns null while loading or after eviction. Do not retain/dispose the
     * result across update/unregister/close; this method does not load assets.
     * <pre>{@code
     * // 第一步：流送 update 后，在同一渲染线程读取当前驻留数据。
     * GiProbeData data = streamer.getResidentData(chunkId);
     * // 第二步：只读取实际重定位与有效性；未驻留时显示未加载状态。
     * if (data != null) {
     *     Vector3f offset = data.getProbeOffset(0, new Vector3f());
     * }
     * // 第三步：不缓存或释放 data，下帧重新查询。
     * }</pre>
     */
    public GiProbeData getResidentData(String id) {
        for (Map.Entry<Chunk, GiProbeData> entry : resident.entrySet()) {
            if (entry.getKey().id.equals(id)) {
                return entry.getValue();
            }
        }
        return null;
    }
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private final Map<Material, GiProbeSet.Binding> materials = new IdentityHashMap<>();
    private List<Chunk> attempted;
    private long attemptedSourceBytes = -1;
    private GiChunkIndex directoryIndex;
    private GiProbeSet atlas;
    private GiPagePool pages;
    private final Set<String> pageAdmissionFailures = new LinkedHashSet<>();
    private long uploadByteBudget = 1024 * 1024;
    private long uploadNanos = 2_000_000;
    private double unloadMultiplier = 1.2;
    private boolean closed;
    private double originX = Double.NaN;
    private double originY;
    private double originZ;

    public GiProbeStreamer(ExecutorService executor, long byteBudget, int maxTextureSize, double loadDistance) {
        if (byteBudget < 4096 || maxTextureSize < 4 || !Double.isFinite(loadDistance) || loadDistance <= 0) {
            throw new IllegalArgumentException("Positive residency budget, texture limit and load distance required");
        }
        this.executor = Objects.requireNonNull(executor);
        budget = byteBudget;
        textureLimit = maxTextureSize;
        distance = loadDistance;
    }

    /**
     * Creates an incremental renderer-backed stream. The fixed pool reserves at
     * most one quarter of byteBudget for GPU payload, plus an equal CPU mirror.
     * Remaining space bounds source/pending data. RGBA32F pages preserve mixed
     * source precision; region uploads are required. Initial texture allocation
     * and one driver call can exceed the soft time budget. Legacy constructor
     * retains snapshot compatibility for renderer-independent callers.
     * <pre>{@code
     * // 第一步：在渲染线程创建固定页池；线程池负责磁盘读取与解压。
     * GiProbeStreamer stream = new GiProbeStreamer(io, 256L << 20, 4096, 200, renderer);
     * // 第二步：每帧最多上传 1 MiB，软时间预算 2ms；至少容纳一行纹理。
     * stream.setUploadBudget(1L << 20, 2_000_000);
     * // 第三步：沿用 register/addMaterial/update；远处数据异步预加载。
     * stream.setUnloadMultiplier(1.3);
     * stream.update(cam.getLocation(), originX, originY, originZ);
     * // 第四步：卸载前 close 恢复材质并释放页池；调用方关闭 io。
     * }</pre>
     */
    public GiProbeStreamer(ExecutorService executor, long byteBudget, int maxTextureSize,
            double loadDistance, com.jme3.renderer.Renderer renderer) {
        this(executor, byteBudget, maxTextureSize, loadDistance);
        pages = new GiPagePool(renderer, maxTextureSize, byteBudget / 4);
        atlas = pages.bindings();
    }

    /** Render-thread configuration; time is soft, byte budget excludes initial allocation and metadata eviction. */
    public void setUploadBudget(long bytes, long nanos) {
        if (bytes < 16384 || nanos <= 0) {
            throw new IllegalArgumentException("At least 16384 bytes and positive upload time required");
        }
        uploadByteBudget = bytes;
        uploadNanos = nanos;
    }

    /** Unload radius / preload radius; >=1 prevents boundary thrashing. */
    public void setUnloadMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < 1) {
            throw new IllegalArgumentException("Finite unload multiplier >= 1 required");
        }
        unloadMultiplier = multiplier;
    }

    public int getPublishedCount() {
        return pages == null ? getResidentCount() : pages.publishedCount();
    }

    /** Render-thread snapshot of resident IDs awaiting space or exceeding page width.
     * Retried automatically each update; existing published chunks remain intact.
     * A source image must be at most min(1024, maxTextureSize) texels wide.
     */
    public Set<String> getPageAdmissionFailures() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(pageAdmissionFailures));
    }

    public long getUploadedBytes() {
        return pages == null ? 0 : pages.uploadedBytes();
    }

    /** Changes runtime GI appearance without reloading/uploading payloads.
     * Render-thread only. Defaults are intensity 1 and 20% boundary fade for raw
     * chunks; scene integrations explicitly use GiProbeVolume settings instead.
     * <pre>{@code
     * // 第一步：register 分块后设置强度和边界渐变。
     * stream.setAppearance("room", 1.5f, 0f);
     * // 第二步：下一次 update 仅更新描述符，不重新读取烘焙数据。
     * stream.update(camera, 0, 0, 0);
     * }</pre>
     */
    public void setAppearance(String id, float intensity, float fadeDistance) {
        setAppearance(id, intensity, fadeDistance, false);
    }

    /** Render-thread appearance update including optional stabilized blending.
     * No payload rebake/reload is required; false restores strict mixing.
     * <pre>{@code
     * // 第一步：仅手动流送扩展需要设置；自动场景服务读取 Volume。
     * stream.setAppearance("room", 1f, 0f, true);
     * // 第二步：正常 update 发布描述符，设 false 恢复原混合。
     * }</pre>
     */
    public void setAppearance(String id, float intensity, float fadeDistance, boolean stabilized) {
        setAppearance(id, intensity, fadeDistance, stabilized, false);
    }

    /** Compatibility overload: smooth is ignored. Render-thread only, with the same lifecycle as the four-argument overload. */
    public void setAppearance(String id, float intensity, float fadeDistance, boolean stabilized, boolean smooth) {
        setAppearance(id, intensity, fadeDistance, stabilized, smooth, false);
    }

    /** Render-thread stencil selection; wide defaults false in other overloads.
     * See GiProbeVolume.setWideProbeSampling for application usage. */
    public void setAppearance(String id, float intensity, float fadeDistance,
            boolean stabilized, boolean smooth, boolean wide) {
        setAppearance(id, intensity, fadeDistance, stabilized, smooth, wide, false);
    }

    /** Render-thread curved-receiver toggle; older overloads preserve false.
     * See GiProbeVolume.setCurvedReceiverCorrection for usage/defaults. */
    public void setAppearance(String id, float intensity, float fadeDistance,
            boolean stabilized, boolean smooth, boolean wide, boolean curved) {
        requireOpen();
        if (!Float.isFinite(intensity) || intensity < 0
                || !Float.isFinite(fadeDistance) || fadeDistance < 0) {
            throw new IllegalArgumentException("Finite nonnegative intensity/fade required");
        }
        Chunk chunk = directory.get(id);
        if (chunk == null) {
            throw new IllegalArgumentException("Unknown GI chunk " + id);
        }
        if (chunk.intensity != intensity || chunk.fadeDistance != fadeDistance || chunk.stabilized != stabilized || chunk.wide != wide || chunk.curved != curved || chunk.smooth) {
            chunk.wide = wide;
            chunk.curved = curved;
            chunk.smooth = false;
            chunk.stabilized = stabilized;
            chunk.intensity = intensity;
            chunk.fadeDistance = fadeDistance;
            attempted = null;
        }
    }

    public void register(Chunk chunk) {
        requireOpen();
        if (directory.containsKey(chunk.id)) {
            throw new IllegalArgumentException("Duplicate GI chunk " + chunk.id);
        }
        directory.put(chunk.id, chunk);
        directoryIndex = null;
    }

    /** Stops requesting a chunk; the next update removes it atomically from the atlas. */
    public void unregister(String id) {
        requireOpen();
        Chunk old = directory.remove(id);
        if (old != null) {
            Load loading = pending.remove(old);
            if (loading != null) {
                loading.discard();
            }
            GiProbeData data = resident.remove(old);
            if (pages != null) {
                pages.retain(directory.keySet());
            }
            if (data != null && !old.borrowed) {
                data.dispose();
            }
        }
        directoryIndex = null;
        failures.remove(id);
        pageAdmissionFailures.remove(id);
    }

    public void retry(String id) {
        requireOpen();
        failures.remove(id);
    }

    public Map<String, Throwable> getFailures() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    public void addMaterial(Material material) {
        requireOpen();
        if (material.getMaterialDef().getMaterialParam("GiVolumeData") == null) {
            throw new IllegalArgumentException("Material lacks the GI volume contract");
        }
        if (!materials.containsKey(material)) {
            materials.put(material, atlas == null ? null : atlas.bind(material, 0.05f));
        }
    }

    public void removeMaterial(Material material) {
        GiProbeSet.Binding binding = materials.remove(material);
        if (binding != null) {
            binding.close();
        }
    }

    public long getResidentBytes() {
        long bytes = 0;
        for (GiProbeData data : resident.values()) {
            bytes += data.getTextureBytes();
        }
        return bytes;
    }

    public long getAtlasBytes() {
        return atlas == null ? 0 : atlas.getTextureBytes();
    }

    public int getResidentCount() {
        return resident.size();
    }

    public int getPendingCount() {
        return pending.size();
    }

    /** Never waits for IO. Pass camera coordinates relative to the supplied absolute origin. */
    public void update(Vector3f camera, double worldOriginX, double worldOriginY, double worldOriginZ) {
        requireOpen();
        if (!Vector3f.isValidVector(camera) || !Double.isFinite(worldOriginX)
                || !Double.isFinite(worldOriginY) || !Double.isFinite(worldOriginZ)) {
            throw new IllegalArgumentException("Finite camera and world origin required");
        }
        double cx = camera.x + worldOriginX;
        double cy = camera.y + worldOriginY;
        double cz = camera.z + worldOriginZ;
        if (directoryIndex == null) {
            directoryIndex = new GiChunkIndex(directory.values());
        }
        List<Chunk> candidates = new ArrayList<>();
        directoryIndex.query(cx, cy, cz, distance * unloadMultiplier, candidates);
        candidates.sort(Comparator.comparingDouble(c -> c.distance(cx, cy, cz)));
        List<Chunk> wanted = new ArrayList<>();
        long reserved = pages == null ? 1024 : pages.bytes() * 2;
        for (Chunk chunk : candidates) {
            double radius = resident.containsKey(chunk) || pending.containsKey(chunk)
                    ? distance * unloadMultiplier : distance;
            if (chunk.distance(cx, cy, cz) <= radius && chunk.bytes <= (budget - reserved) / (pages == null ? 5 : 1)
                    && wanted.size() < GiProbeSet.MAX_VOLUMES) {
                wanted.add(chunk);
                reserved += chunk.bytes * (pages == null ? 5 : 1);
            }
        }
        Iterator<Map.Entry<Chunk, Load>> loads = pending.entrySet().iterator();
        while (loads.hasNext()) {
            Map.Entry<Chunk, Load> load = loads.next();
            if (!wanted.contains(load.getKey())) {
                load.getValue().discard();
                loads.remove();
                continue;
            }
            if (!load.getValue().isDone()) {
                continue;
            }
            loads.remove();
            try {
                GiProbeData data = load.getValue().get();
                if (data.getTextureBytes() > load.getKey().bytes) {
                    data.dispose();
                    throw new IllegalArgumentException("GI loader exceeded declared payload bound");
                }
                if (wanted.contains(load.getKey())) {
                    resident.put(load.getKey(), data);
                } else {
                    data.dispose();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failures.put(load.getKey().id, exception);
            } catch (ExecutionException | RuntimeException exception) {
                failures.put(load.getKey().id, exception);
            }
        }
        for (Chunk chunk : wanted) {
            if (!resident.containsKey(chunk) && !pending.containsKey(chunk) && !failures.containsKey(chunk.id)
                    && pending.size() < 2) {
                long used = getResidentBytes() + getAtlasBytes() * 2;
                for (Chunk loading : pending.keySet()) {
                    used += loading.bytes;
                }
                if (chunk.bytes <= budget - used) {
                    if (chunk.borrowed) {
                        try {
                            resident.put(chunk, chunk.loader.call());
                        } catch (Exception exception) {
                            failures.put(chunk.id, exception);
                        }
                        continue;
                    }
                    Load load = new Load(chunk.loader);
                    try {
                        executor.execute(load);
                        pending.put(chunk, load);
                    } catch (RejectedExecutionException exception) {
                        failures.put(chunk.id, exception);
                    }
                }
            }
        }
        List<Chunk> selected = new ArrayList<>();
        for (Chunk chunk : wanted) {
            if (resident.containsKey(chunk)) {
                selected.add(chunk);
            }
        }
        boolean rebased = worldOriginX != originX || worldOriginY != originY || worldOriginZ != originZ;
        long sourceBytes = getResidentBytes() + pendingBytes();
        if (pages != null) {
            Set<String> ids = new HashSet<>();
            for (Chunk chunk : wanted) {
                ids.add(chunk.id);
            }
            pages.retain(ids);
            pageAdmissionFailures.clear();
            for (Chunk chunk : selected) {
                Vector3f offset = new Vector3f((float) (chunk.x - worldOriginX),
                        (float) (chunk.y - worldOriginY), (float) (chunk.z - worldOriginZ));
                float fade = chunk.fadeDistance >= 0 ? chunk.fadeDistance
                        : Math.min(chunk.size.x, Math.min(chunk.size.y, chunk.size.z)) * 0.2f;
                if (!pages.offer(chunk.id, resident.get(chunk), offset, fade)) {
                    pageAdmissionFailures.add(chunk.id);
                }
                pages.appearance(chunk.id, chunk.intensity, fade, chunk.stabilized, chunk.smooth, chunk.wide, chunk.curved);
                pages.relocate(chunk.id, offset);
            }
            pages.upload(uploadByteBudget, uploadNanos);
            originX = worldOriginX;
            originY = worldOriginY;
            originZ = worldOriginZ;
        } else if (!selected.equals(attempted) || rebased || sourceBytes != attemptedSourceBytes) {
            // Remember the requested set, including entries rejected by atlas
            // padding/limits, so a stable camera cannot rebuild every frame.
            attempted = new ArrayList<>(selected);
            attemptedSourceBytes = sourceBytes;
            GiProbeSet replacement = null;
            while (!selected.isEmpty()) {
                List<GiProbeSet.Entry> entries = new ArrayList<>();
                for (Chunk chunk : selected) {
                    entries.add(new GiProbeSet.Entry(resident.get(chunk), new Vector3f((float) (chunk.x - worldOriginX),
                            (float) (chunk.y - worldOriginY), (float) (chunk.z - worldOriginZ)),
                            chunk.fadeDistance >= 0 ? chunk.fadeDistance
                                    : Math.min(chunk.size.x, Math.min(chunk.size.y, chunk.size.z)) * 0.2f, chunk.intensity, chunk.stabilized, chunk.smooth, chunk.wide, chunk.curved));
                }
                try {
                    replacement = new GiProbeSet(entries, textureLimit,
                            Math.max(0, (budget - getResidentBytes() - pendingBytes() - getAtlasBytes() * 2) / 2));
                    break;
                } catch (IllegalArgumentException budgetExceeded) {
                    selected.remove(selected.size() - 1);
                }
            }
            for (GiProbeSet.Binding binding : materials.values()) {
                if (binding != null) {
                    binding.close();
                }
            }
            GiProbeSet previous = atlas;
            atlas = replacement;
            for (Material material : materials.keySet()) {
                materials.put(material, atlas == null ? null : atlas.bind(material, 0.05f));
            }
            if (previous != null) {
                previous.close();
            }
            originX = worldOriginX;
            originY = worldOriginY;
            originZ = worldOriginZ;
        }
        Iterator<Map.Entry<Chunk, GiProbeData>> old = resident.entrySet().iterator();
        while (old.hasNext()) {
            Map.Entry<Chunk, GiProbeData> entry = old.next();
            if (!wanted.contains(entry.getKey())) {
                if (!entry.getKey().borrowed) {
                    entry.getValue().dispose();
                }
                old.remove();
                attempted = null;
            }
        }
    }

    private long pendingBytes() {
        long bytes = 0;
        for (Chunk chunk : pending.keySet()) {
            bytes += chunk.bytes;
        }
        return bytes;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("GI streamer is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (GiProbeSet.Binding binding : materials.values()) {
            if (binding != null) {
                binding.close();
            }
        }
        materials.clear();
        if (pages != null) {
            pages.close();
            pages = null;
            atlas = null;
        } else if (atlas != null) {
            atlas.close();
            atlas = null;
        }
        for (Map.Entry<Chunk, GiProbeData> entry : resident.entrySet()) {
            if (!entry.getKey().borrowed) {
                entry.getValue().dispose();
            }
        }
        resident.clear();
        for (Load future : pending.values()) {
            future.discard();
        }
        pending.clear();
    }
}

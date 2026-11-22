package com.jme3.environment.gi;

import com.jme3.bounding.BoundingBox;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans adaptive probe bricks where surfaces need more samples. Surface patches are sampled on
 * both sides, rejecting solid interiors and unsafe clearance. Candidate bricks
 * are credited only when their actual relocated interpolation neighbors can see
 * a patch; bounding-box overlap alone is not coverage. Greedy allocation fills
 * uncovered patches before spending remaining slots on deeper refinement.
 *
 * This retains regular 5x5x5 brick topology for existing shader interpolation.
 * It does not place arbitrary points on a mesh shell. Closed solids reject their
 * interior; open sheets retain both free sides. Classification/relocation is
 * constrained to the original cell, so unusable probes are disabled, not moved
 * through walls. Finite surface samples are a diagnostic, not an error bound on GI.
 *
 * Run on the scene thread with fixed geometry and updated world transforms.
 * Planning is synchronous and bounded by cell, triangle and surface-sample limits.
 * No source scene or baked payload is changed. Streamed bricks still consume the
 * global 16-volume limit; insufficient coverage is explicitly reported.
 * <pre>{@code
 * // 第一步：固定场景姿态，更新几何状态；默认最多 4 块、最多 2 层。
 * rootNode.updateGeometricState();
 * GiAdaptiveProbeLayout.Settings layout = new GiAdaptiveProbeLayout.Settings();
 * layout.maxRefinements = 4;
 * layout.maxDepth = 2;
 * // 第二步：检查表面采样覆盖率；未覆盖时提高预算或拆分 Volume，不能视为完全覆盖。
 * GiAdaptiveProbeLayout.Plan plan = GiAdaptiveProbeLayout.planDetailed(rootNode, volume, layout);
 * System.out.println(plan.getCoveredTargets() + "/" + plan.getSurfaceTargets());
 * // 第三步：实际烘焙使用 GiAdaptiveBakeJob，自动匹配烘焙间距与安全距离。
 * GiAdaptiveBakeJob job = GiAdaptiveBakeJob.start(renderManager, rootNode, volume,
 *         new GiSceneBaker.Settings(), layout, view -> { }, () -> false, n -> { });
 * // 第四步：每帧 step；成功后保存 J3O 和引用数据，SimpleApplication 自动加载。
 * // 设置 maxRefinements=0 并重新烘焙可恢复普通网格，旧结果仅在成功后替换。
 * }</pre>
 */
public final class GiAdaptiveProbeLayout {
    static final String GENERATED = "jme.gi.adaptive.refinement";

    private GiAdaptiveProbeLayout() {
    }

    /** Quality/cost controls copied by the bake job; lengths are world units. */
    public static final class Settings {
        /** Maximum additional volumes; default 4, range 0..15. */
        public int maxRefinements = 4;
        /** Maximum refinement levels, default 2, range 1..4. */
        public int maxDepth = 2;
        /** Do not refine cells smaller than twice this spacing; default 0.25. */
        public float minimumSpacing = 0.25f;
        /** Maximum sampled free-space surface targets per planning level. */
        public int maxSurfaceSamples = 8192;
        /** World-space clearance for standalone planning; bake jobs use bake settings. */
        public float surfaceClearance = 0.05f;
        /** Relative clearance matching GiSceneBaker, default 2% of the smallest grid step. */
        public float relativeClearance = 0.02f;
        public int maxCells = 32768;
        public long maxTriangleCellTests = 2_000_000;

        Settings copy() {
            Settings result = new Settings();
            result.maxRefinements = maxRefinements;
            result.maxDepth = maxDepth;
            result.minimumSpacing = minimumSpacing;
            result.maxSurfaceSamples = maxSurfaceSamples;
            result.surfaceClearance = surfaceClearance;
            result.relativeClearance = relativeClearance;
            result.maxCells = maxCells;
            result.maxTriangleCellTests = maxTriangleCellTests;
            return result;
        }
    }

    /** Diagnostics and an unmodifiable list of generated volumes. The volumes are editable, detached, and in source-local coordinates. */
    public static final class Plan {
        private final List<GiProbeVolume> volumes;
        private final int targets;
        private final int covered;

        private Plan(List<GiProbeVolume> volumes, int targets, int covered) {
            this.volumes = java.util.Collections.unmodifiableList(volumes);
            this.targets = targets;
            this.covered = covered;
        }

        public List<GiProbeVolume> getVolumes() {
            return volumes;
        }

        /** Sampled reachable surface targets at the first refinement level. */
        public int getSurfaceTargets() {
            return targets;
        }

        /** Targets with sufficient unobstructed relocated-neighbor support. */
        public int getCoveredTargets() {
            return covered;
        }
    }

    /** Returns unattached volumes; use planDetailed to inspect coverage shortfalls. */
    public static List<GiProbeVolume> plan(Spatial scene, GiProbeVolume source, Settings settings) {
        return planDetailed(scene, source, settings).getVolumes();
    }

    /** See the class example. Does not mutate the scene; lengths are world units. */
    public static Plan planDetailed(Spatial scene, GiProbeVolume source, Settings settings) {
        return planDetailed(scene, source, settings, null);
    }

    static Plan planDetailed(Spatial scene, GiProbeVolume source, Settings settings,
            GiSceneBaker.ProbeValidator validator) {
        if (settings.maxDepth < 1 || settings.maxDepth > 4 || settings.maxRefinements < 0
                || settings.maxRefinements >= GiProbeSet.MAX_VOLUMES
                || !Float.isFinite(settings.surfaceClearance) || settings.surfaceClearance <= 0
                || !Float.isFinite(settings.relativeClearance) || settings.relativeClearance < 0
                || settings.maxSurfaceSamples < 1) {
            throw new IllegalArgumentException("Invalid adaptive depth, refinement budget or surface sampling settings");
        }
        List<GiProbeVolume> result = new ArrayList<>();
        // The capture scene is fixed for the entire plan; share its topology
        // snapshot across levels rather than rebuilding it for each parent.
        GiSceneBaker.ProbeValidator placement = settings.maxRefinements == 0
                ? null : validator != null ? validator : GiProbePlacement.validator(scene);
        GiSurfaceCoverage coverageCheck = settings.maxRefinements == 0 ? null
                : new GiSurfaceCoverage(scene, placement, settings.surfaceClearance, settings.relativeClearance);
        int[] coverageReport = new int[2];
        java.util.Map<Mesh, int[]> topology = new java.util.IdentityHashMap<>();
        List<GiProbeVolume> parents = new ArrayList<>();
        parents.add(source);
        for (int depth = 1; depth <= settings.maxDepth && result.size() < settings.maxRefinements; depth++) {
            int remaining = settings.maxRefinements - result.size();
            int quota = remaining; // Cover this level before reserving slots for finer islands.
            List<GiProbeVolume> next = new ArrayList<>();
            int parentsLeft = parents.size();
            for (GiProbeVolume parent : parents) {
                Settings local = settings.copy();
                int available = quota - next.size();
                local.maxRefinements = Math.min((available + parentsLeft - 1) / parentsLeft, remaining);
                parentsLeft--;
                if (local.maxRefinements <= 0) {
                    break;
                }
                for (GiProbeVolume brick : planLevel(scene, parent, local, coverageCheck, topology, depth == 1 ? coverageReport : new int[2])) {
                    Vector3f world = parent.localToWorld(brick.getLocalTranslation(), new Vector3f());
                    Vector3f extent = brick.getCoverage().mult(parent.getWorldScale());
                    brick.configure(extent.divide(source.getWorldScale()), 5, 5, 5);
                    brick.setLocalTranslation(source.worldToLocal(world, new Vector3f()));
                    brick.setUserData("jme.gi.adaptive.level", depth);
                    result.add(brick);
                    // A detached world-space descriptor guides the next level;
                    // published bricks always remain direct children of source.
                    GiProbeVolume descriptor = new GiProbeVolume();
                    descriptor.configure(extent, 5, 5, 5);
                    descriptor.setLocalTranslation(world);
                    descriptor.updateGeometricState();
                    next.add(descriptor);
                }
            }
            parents = next;
            if (parents.isEmpty()) {
                break;
            }
        }
        return new Plan(result, coverageReport[0], coverageReport[1]);
    }

    private static List<GiProbeVolume> planLevel(Spatial scene, GiProbeVolume source, Settings settings,
            GiSurfaceCoverage coverageCheck, java.util.Map<Mesh, int[]> topology, int[] report) {
        if (settings.maxRefinements < 0 || settings.maxRefinements >= GiProbeSet.MAX_VOLUMES
                || !Float.isFinite(settings.minimumSpacing) || settings.minimumSpacing <= 0
                || settings.maxCells < 1 || settings.maxTriangleCellTests < 1) {
            throw new IllegalArgumentException("Invalid adaptive GI budget");
        }
        List<GiProbeVolume> result = new ArrayList<>();
        GiProbeGrid grid = source.getBakeGrid();
        Vector3f spacing = grid.getSpacing();
        if (settings.maxRefinements == 0
                || Math.min(spacing.x, Math.min(spacing.y, spacing.z)) < settings.minimumSpacing * 2) {
            return result;
        }
        Vector3f counts = grid.getCounts();
        int nx = (int) counts.x - 1;
        int ny = (int) counts.y - 1;
        int nz = (int) counts.z - 1;
        long count = (long) nx * ny * nz;
        if (count > settings.maxCells) {
            throw new IllegalArgumentException("Adaptive planning cell budget exceeded; split the volume");
        }
        Cell[] cells = new Cell[(int) count];
        Vector3f origin = source.getWorldTranslation();
        for (int z = 0; z < nz; z++) {
            for (int y = 0; y < ny; y++) {
                for (int x = 0; x < nx; x++) {
                    cells[x + nx * (y + ny * z)] = new Cell(x, y, z,
                            origin.add(new Vector3f((x + 0.5f) * spacing.x,
                                    (y + 0.5f) * spacing.y, (z + 0.5f) * spacing.z)), spacing);
                }
            }
        }
        long[] work = {0};
        int[] rawSamples = {0};
        scene.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof Geometry) || !GiProbePlacement.participates(spatial)) {
                return;
            }
            Geometry geometry = (Geometry) spatial;
            Mesh mesh = geometry.getMesh();
            if (mesh.getMode() != Mesh.Mode.Triangles && mesh.getMode() != Mesh.Mode.TriangleStrip
                    && mesh.getMode() != Mesh.Mode.TriangleFan) {
                return;
            }
            int[] islands = topology.computeIfAbsent(mesh, GiProbeSolidClassifier::connectedComponents);
            java.util.Map<Integer, Object> surfaceIds = new java.util.HashMap<>();
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f c = new Vector3f();
            for (int triangle = 0; triangle < mesh.getTriangleCount(); triangle++) {
                if (++work[0] > settings.maxTriangleCellTests) {
                    throw new IllegalArgumentException("Adaptive geometry budget exceeded; split the volume");
                }
                Object surfaceId = surfaceIds.computeIfAbsent(islands[triangle], ignored -> new Object());
                mesh.getTriangle(triangle, a, b, c);
                geometry.localToWorld(a, a);
                geometry.localToWorld(b, b);
                geometry.localToWorld(c, c);
                Vector3f normal = b.subtract(a).cross(c.subtract(a));
                float area = normal.length() * 0.5f;
                if (area <= 1.0e-12f) {
                    continue;
                }
                normal.normalizeLocal();
                int x0 = lower(a.x, b.x, c.x, origin.x, spacing.x, nx);
                int y0 = lower(a.y, b.y, c.y, origin.y, spacing.y, ny);
                int z0 = lower(a.z, b.z, c.z, origin.z, spacing.z, nz);
                int x1 = upper(a.x, b.x, c.x, origin.x, spacing.x, nx);
                int y1 = upper(a.y, b.y, c.y, origin.y, spacing.y, ny);
                int z1 = upper(a.z, b.z, c.z, origin.z, spacing.z, nz);
                for (int z = z0; z <= z1; z++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int x = x0; x <= x1; x++) {
                            if (++work[0] > settings.maxTriangleCellTests) {
                                throw new IllegalArgumentException("Adaptive intersection budget exceeded; split the volume");
                            }
                            Cell cell = cells[x + nx * (y + ny * z)];
                            if (cell.bounds.intersects(a, b, c)) {
                                cell.area = 1;
                                Vector3f nearest = GiSurfaceCoverage.closest(cell.bounds.getCenter(), a, b, c, normal);
                                int before = cell.rawTargets.size();
                                cell.addSurface(nearest, normal, surfaceId);
                                cell.addSurface(nearest, normal.negate(), surfaceId);
                                rawSamples[0] += cell.rawTargets.size() - before;
                                if (rawSamples[0] > (long) settings.maxSurfaceSamples * 8) {
                                    throw new IllegalArgumentException("Adaptive raw surface budget exceeded; split the volume");
                                }
                            }
                        }
                    }
                }
            }
        });
        List<Cell> occupied = new ArrayList<>();
        for (Cell cell : cells) {
            if (cell.area > 0) {
                occupied.add(cell);
            }
        }
        List<GiSurfaceCoverage.Target> targets = new ArrayList<>();
        java.util.Map<Object, Integer> surfaceTotals = new java.util.IdentityHashMap<>();
        Vector3f sourceHigh = origin.add(source.getCoverage().mult(source.getWorldScale()));
        for (Cell cell : occupied) {
            for (GiSurfaceCoverage.Target raw : cell.rawTargets) {
                GiSurfaceCoverage.Target target = coverageCheck.target(raw.position, raw.normal, raw.surface, spacing.mult(0.5f));
                if (target == null || !inside(target.position, origin, sourceHigh)) {
                    continue;
                }
                if (targets.size() >= settings.maxSurfaceSamples) {
                    throw new IllegalArgumentException("Adaptive surface-sample budget exceeded; split the volume");
                }
                targets.add(target);
                surfaceTotals.merge(target.surface, 1, Integer::sum);
            }
        }
        for (GiSurfaceCoverage.Target target : targets) {
            target.weight = 1.0 / surfaceTotals.get(target.surface);
        }
        report[0] = targets.size();
        Vector3f localSpacing = source.getGrid().getSpacing();
        Vector3f coverage = source.getCoverage();
        // All candidates compete against the same uncovered surface demands.
        for (Cell cell : occupied) {
            Vector3f low = new Vector3f(Math.max(0, cell.x - 0.5f) * localSpacing.x,
                    Math.max(0, cell.y - 0.5f) * localSpacing.y, Math.max(0, cell.z - 0.5f) * localSpacing.z);
            Vector3f high = new Vector3f(Math.min(coverage.x, (cell.x + 1.5f) * localSpacing.x),
                    Math.min(coverage.y, (cell.y + 1.5f) * localSpacing.y),
                    Math.min(coverage.z, (cell.z + 1.5f) * localSpacing.z));
            cell.worldLow = source.localToWorld(low, new Vector3f());
            cell.step = high.subtract(low).multLocal(source.getWorldScale()).multLocal(0.25f);
        }
        List<Cell> selected = new ArrayList<>();
        while (result.size() < settings.maxRefinements) {
            Cell cell = null;
            double best = 0;
            // Bounding coverage is only an upper bound. Lazy evaluation avoids
            // validating every 125-probe brick when it cannot beat the best gain.
            occupied.sort(Comparator.comparingDouble((Cell candidate) -> candidate.gain(targets, false)).reversed());
            for (Cell candidate : occupied) {
                if (selected.contains(candidate)
                        || Math.min(candidate.step.x, Math.min(candidate.step.y, candidate.step.z)) < settings.minimumSpacing) {
                    continue;
                }
                if (candidate.gain(targets, false) <= best) {
                    break;
                }
                if (candidate.support == null) {
                    Vector3f[] probes = coverageCheck.probes(candidate.worldLow, candidate.step);
                    candidate.support = new java.util.BitSet(targets.size());
                    for (int i = 0; i < targets.size(); i++) {
                        if (coverageCheck.supports(targets.get(i), candidate.worldLow, candidate.step, probes)) {
                            candidate.support.set(i);
                        }
                    }
                }
                double gain = candidate.gain(targets, true);
                if (gain > best) {
                    best = gain;
                    cell = candidate;
                }
            }
            if (cell == null) {
                break;
            }
            selected.add(cell);
            for (int i = cell.support.nextSetBit(0); i >= 0; i = cell.support.nextSetBit(i + 1)) {
                targets.get(i).covered = true;
            }
            // Half-cell halo on both sides gives continuous coarse/fine overlap.
            Vector3f low = new Vector3f(Math.max(0, cell.x - 0.5f) * localSpacing.x,
                    Math.max(0, cell.y - 0.5f) * localSpacing.y, Math.max(0, cell.z - 0.5f) * localSpacing.z);
            Vector3f high = new Vector3f(Math.min(coverage.x, (cell.x + 1.5f) * localSpacing.x),
                    Math.min(coverage.y, (cell.y + 1.5f) * localSpacing.y),
                    Math.min(coverage.z, (cell.z + 1.5f) * localSpacing.z));
            GiProbeVolume refinement = new GiProbeVolume();
            refinement.setName("GI refinement " + cell.x + "/" + cell.y + "/" + cell.z);
            refinement.configure(high.subtract(low), 5, 5, 5);
            refinement.setLocalTranslation(low);
            refinement.setIntensity(source.getIntensity());
            refinement.setUserData(GENERATED, true);
            result.add(refinement);
        }
        report[1] = (int) targets.stream().filter(target -> target.covered).count();
        return result;
    }

    private static boolean inside(Vector3f point, Vector3f low, Vector3f high) {
        return point.x >= low.x && point.y >= low.y && point.z >= low.z
                && point.x <= high.x && point.y <= high.y && point.z <= high.z;
    }

    private static int lower(float a, float b, float c, float origin, float spacing, int count) {
        return Math.max(0, Math.min(count, (int) Math.floor((Math.min(a, Math.min(b, c)) - origin) / spacing - 0.0001f)));
    }

    private static int upper(float a, float b, float c, float origin, float spacing, int count) {
        return Math.max(-1, Math.min(count - 1, (int) Math.floor((Math.max(a, Math.max(b, c)) - origin) / spacing + 0.0001f)));
    }

    private static final class Cell {
        final int x;
        final int y;
        final int z;
        final BoundingBox bounds;
        float area;
        Vector3f worldLow;
        Vector3f step;
        java.util.BitSet support;
        final List<GiSurfaceCoverage.Target> rawTargets = new ArrayList<>();

        Cell(int x, int y, int z, Vector3f center, Vector3f spacing) {
            this.x = x;
            this.y = y;
            this.z = z;
            bounds = new BoundingBox(center, spacing.x * 0.5001f, spacing.y * 0.5001f, spacing.z * 0.5001f);
        }

        void addSurface(Vector3f position, Vector3f normal, Object surface) {
            for (int i = 0; i < rawTargets.size(); i++) {
                GiSurfaceCoverage.Target existing = rawTargets.get(i);
                if (existing.surface == surface && existing.normal.dot(normal) > 0.95f) {
                    if (position.distanceSquared(bounds.getCenter()) < existing.position.distanceSquared(bounds.getCenter())) {
                        rawTargets.set(i, new GiSurfaceCoverage.Target(position, normal, surface));
                    }
                    return;
                }
            }
            rawTargets.add(new GiSurfaceCoverage.Target(position, normal, surface));
        }

        double gain(List<GiSurfaceCoverage.Target> targets, boolean exact) {
            Vector3f high = worldLow.add(step.mult(4));
            double result = 0;
            for (int i = 0; i < targets.size(); i++) {
                GiSurfaceCoverage.Target target = targets.get(i);
                if (!target.covered && (exact ? support.get(i) : inside(target.position, worldLow, high))) {
                    result += target.weight;
                }
            }
            return result;
        }
    }
}

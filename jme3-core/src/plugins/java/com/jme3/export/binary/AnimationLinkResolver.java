package com.jme3.export.binary;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.util.HasLocalTransform;
import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.math.Transform;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves external J3O clips after the binary scene has been read in full.
 * References are stored on the target composer spatial. A library link imports
 * its local and recursively linked clips. A clip link selects one stable ID.
 * Resolution never mutates a source asset and attaches tracks to the current
 * model's own targets. Missing, incompatible or cyclic links are logged and
 * skipped on model load, so embedded clips and unrelated branches still work.
 *
 * <pre>{@code
 * // 第一步：在编辑器选择角色 A，再关联动画库 B（或选择 B 的一个片段）。
 * // 第二步：保存 A.j3o；文件只保存引用，B.j3o 仍放在资产目录。
 * Spatial actor = assets.loadModel("Models/A.j3o");
 * AnimComposer composer = actor.getControl(AnimComposer.class);
 * // 第三步：使用编辑器显示的剪辑 ID 播放绑定到 A 实例的动作。
 * composer.setCurrentAction(composer.getAnimClipsNames().iterator().next());
 * // 第四步：编辑器删除关联再保存可恢复内置剪辑，重新加载可更新外部动作。
 * }</pre>
 * Call editing methods on the owning scene thread, or on a private, unattached
 * model. Raw BinaryImporter loads can call resolveModel explicitly.
 */
public final class AnimationLinkResolver {
    public static final String USER_DATA = "jme.anim.externalClips.v1";
    private static final Logger LOG = Logger.getLogger(AnimationLinkResolver.class.getName());
    private static final int MAX_DEPTH = 128;

    private AnimationLinkResolver() {
    }

    /** A canonical project-relative J3O key and, optionally, one resolved clip ID. */
    public static final class Link {
        private final String asset;
        private final String clip;

        public Link(String asset, String clip) {
            String canonical = new ModelKey(asset.replace('\\', '/')).getName();
            if (canonical.startsWith("/") || canonical.startsWith("../")
                    || canonical.contains(":") || !canonical.toLowerCase(java.util.Locale.ROOT).endsWith(".j3o")) {
                throw new IllegalArgumentException("Expected a project-relative J3O asset: " + asset);
            }
            this.asset = canonical;
            this.clip = clip == null ? "" : clip;
        }

        public String asset() {
            return asset;
        }

        public String clip() {
            return clip;
        }

        @Override
        public String toString() {
            return clip.isEmpty() ? asset + " (library)" : asset + " → " + clip;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Link)) {
                return false;
            }
            Link link = (Link) other;
            return asset.equals(link.asset) && clip.equals(link.clip);
        }

        @Override
        public int hashCode() {
            return 31 * asset.hashCode() + clip.hashCode();
        }
    }

    /** Reads the ordered references saved on the composer owner. */
    public static List<Link> links(Spatial owner) {
        String data = owner.getUserData(USER_DATA);
        List<Link> result = new ArrayList<>();
        if (data != null && !data.isEmpty()) {
            for (String line : data.split("\\n")) {
                String[] values = line.split(" ", -1);
                if (values.length != 2) {
                    throw new IllegalArgumentException("Invalid animation link data");
                }
                result.add(new Link(decode(values[0]), decode(values[1])));
            }
        }
        return result;
    }

    /** Persists a previously validated set of links, without embedding tracks. */
    public static void writeLinks(Spatial owner, List<Link> links) {
        List<String> rows = new ArrayList<>();
        for (Link link : links) {
            rows.add(encode(link.asset()) + " " + encode(link.clip()));
        }
        owner.setUserData(USER_DATA, rows.isEmpty() ? null : String.join("\n", rows));
    }

    /** Lists selectable IDs including clips contributed by a source's libraries. */
    public static List<String> clipIds(AssetManager assets, String asset) throws IOException {
        Scan scan = new Scan(assets, true);
        return new ArrayList<>(scan.collect(new Link(asset, null), new ArrayDeque<>()).keySet());
    }

    /**
     * Checks the proposed graph for cycles and binds its clips without editing
     * the owner. Duplicate direct references are rejected. Shared transitive
     * clips are bound only once.
     */
    public static List<AnimClip> validate(AssetManager assets, String targetAsset,
            Spatial owner, List<Link> proposed) throws IOException {
        if (new HashSet<>(proposed).size() != proposed.size()) {
            throw new IOException("Animation link already exists");
        }
        Scan scan = new Scan(assets, true);
        Deque<String> path = new ArrayDeque<>();
        path.addLast(new Link(targetAsset, null).asset());
        scan.checkCycles(proposed, path, new HashSet<>());
        return scan.bind(owner, proposed, path);
    }

    /**
     * Turns a private model copy into a self-contained animation export.
     * The copy must have the same hierarchy as the original asset. Clip IDs
     * stay intact so existing animation graphs still resolve their actions.
     *
     * @param assets asset manager containing the source libraries
     * @param targetAsset key of the original model
     * @param copy detached export copy, modified by this method
     * @throws IOException if any reference cannot be validated
     */
    public static void embed(AssetManager assets, String targetAsset, Spatial copy) throws IOException {
        List<Spatial> owners = linkOwners(copy);
        Map<Spatial, List<AnimClip>> resolved = new LinkedHashMap<>();
        for (Spatial owner : owners) {
            resolved.put(owner, validate(assets, targetAsset, owner, links(owner)));
        }
        for (Map.Entry<Spatial, List<AnimClip>> entry : resolved.entrySet()) {
            Spatial owner = entry.getKey();
            AnimComposer composer = owner.getControl(AnimComposer.class);
            if (composer == null) {
                composer = new AnimComposer();
                owner.addControl(composer);
            }
            Set<String> local = new HashSet<>();
            for (AnimClip clip : composer.getEmbeddedAnimClips()) {
                local.add(clip.getName());
            }
            for (AnimClip clip : entry.getValue()) {
                if (local.add(clip.getName())) {
                    composer.addAnimClip(clip);
                }
            }
            composer.setLinkedAnimClips(Collections.emptyList());
            writeLinks(owner, Collections.emptyList());
        }
    }

    /** Installs the valid subset of a model's links, logging errors per reference. */
    public static void resolveModel(AssetManager assets, String modelAsset, Spatial root) {
        List<Spatial> owners = linkOwners(root);
        if (owners.isEmpty()) {
            return;
        }
        Scan scan = new Scan(assets, false);
        Deque<String> path = new ArrayDeque<>();
        path.addLast(modelAsset);
        for (Spatial owner : owners) {
            try {
                AnimComposer composer = owner.getControl(AnimComposer.class);
                if (composer == null) {
                    composer = new AnimComposer();
                    owner.addControl(composer);
                }
                composer.setLinkedAnimClips(scan.bind(owner, links(owner), path));
            } catch (IOException | RuntimeException error) {
                LOG.warning(modelAsset + ": " + error.getMessage());
            }
        }
    }

    private static List<Spatial> linkOwners(Spatial root) {
        List<Spatial> owners = new ArrayList<>();
        root.depthFirstTraversal(spatial -> {
            if (spatial.getUserData(USER_DATA) != null) {
                owners.add(spatial);
            }
        });
        return owners;
    }

    private static final class Scan {
        private final AssetManager assets;
        private final boolean strict;
        private final Map<String, Spatial> loaded = new HashMap<>();

        Scan(AssetManager assets, boolean strict) {
            this.assets = assets;
            this.strict = strict;
        }

        void checkCycles(List<Link> links, Deque<String> path, Set<String> visited) throws IOException {
            for (Link link : links) {
                String asset = link.asset();
                if (path.contains(asset) || path.size() >= MAX_DEPTH) {
                    throw cycle(path, asset);
                }
                if (!visited.add(asset)) {
                    continue;
                }
                path.addLast(asset);
                try {
                    for (Spatial owner : linkOwners(raw(asset))) {
                        checkCycles(AnimationLinkResolver.links(owner), path, visited);
                    }
                } finally {
                    path.removeLast();
                }
            }
        }

        Map<String, AnimClip> collect(Link link, Deque<String> path) throws IOException {
            Map<String, AnimClip> result = new LinkedHashMap<>();
            String asset = link.asset();
            if (path.contains(asset) || path.size() >= MAX_DEPTH) {
                fail(cycle(path, asset));
                return result;
            }
            path.addLast(asset);
            try {
                Spatial root = raw(asset);
                List<Spatial> owners = new ArrayList<>();
                root.depthFirstTraversal(spatial -> {
                    AnimComposer composer = spatial.getControl(AnimComposer.class);
                    if (composer != null || spatial.getUserData(USER_DATA) != null) {
                        owners.add(spatial);
                    }
                    if (composer != null) {
                        for (AnimClip clip : composer.getEmbeddedAnimClips()) {
                            result.put(clipId(asset, ownerPath(root, spatial), clip.getName()), clip);
                        }
                    }
                });
                if (!link.clip().isEmpty() && result.containsKey(link.clip())) {
                    // An exact embedded clip does not depend on unrelated library edges.
                    return Collections.singletonMap(link.clip(), result.get(link.clip()));
                }
                for (Spatial owner : owners) {
                    try {
                        for (Link nested : links(owner)) {
                            result.putAll(collect(nested, path));
                        }
                    } catch (IllegalArgumentException error) {
                        fail(new IOException(asset + ": invalid animation link", error));
                    }
                }
                if (!link.clip().isEmpty()) {
                    AnimClip selected = result.get(link.clip());
                    if (selected == null) {
                        throw new IOException("Animation clip not found in " + asset + ": " + link.clip());
                    }
                    return Collections.singletonMap(link.clip(), selected);
                }
                return result;
            } catch (IOException | RuntimeException error) {
                fail(new IOException(String.join(" → ", path) + ": " + error.getMessage(), error));
                return result;
            } finally {
                path.removeLast();
            }
        }

        List<AnimClip> bind(Spatial owner, List<Link> links, Deque<String> path) throws IOException {
            Map<String, AnimClip> all = new LinkedHashMap<>();
            for (Link link : new LinkedHashSet<>(links)) {
                Map<String, AnimClip> selected = collect(link, path);
                if (strict && selected.isEmpty()) {
                    throw new IOException("No animation clips found in " + link.asset());
                }
                all.putAll(selected);
            }
            List<AnimClip> result = new ArrayList<>();
            if (all.isEmpty()) {
                return result;
            }
            Targets targets = new Targets(owner);
            for (Map.Entry<String, AnimClip> entry : all.entrySet()) {
                try {
                    result.add(targets.bind(entry.getKey(), entry.getValue()));
                } catch (IOException error) {
                    fail(error);
                }
            }
            return result;
        }

        private Spatial raw(String asset) throws IOException {
            Spatial cached = loaded.get(asset);
            if (cached != null) {
                return cached;
            }
            AssetInfo info = assets.locateAsset(new ModelKey(asset));
            if (info == null) {
                throw new IOException("Missing animation asset " + asset);
            }
            BinaryImporter importer = new BinaryImporter();
            importer.setAssetManager(assets);
            try (InputStream stream = info.openStream()) {
                Object content = importer.load(stream);
                if (!(content instanceof Spatial)) {
                    throw new IOException("Animation asset is not a model: " + asset);
                }
                Spatial root = (Spatial) content;
                loaded.put(asset, root);
                return root;
            }
        }

        private void fail(IOException error) throws IOException {
            if (strict) {
                throw error;
            }
            LOG.warning(error.getMessage());
        }
    }

    private static IOException cycle(Deque<String> path, String asset) {
        return new IOException("Animation link cycle or depth limit: "
                + String.join(" → ", path) + " → " + asset);
    }

    private static final class Targets {
        private final Map<String, List<Joint>> joints = new LinkedHashMap<>();
        private final Map<String, List<Spatial>> nodes = new LinkedHashMap<>();
        private final Set<String> ambiguousJoints = new HashSet<>();

        Targets(Spatial owner) {
            Spatial root = owner;
            while (root.getParent() != null) {
                root = root.getParent();
            }
            // A composer can drive sibling meshes. Restrict to its own subtree
            // when it already contains a rig, otherwise use the model root.
            Set<SkinningControl> skins = rigs(owner);
            Spatial scope = owner;
            if (skins.isEmpty()) {
                skins = rigs(root);
                scope = root;
            }
            for (SkinningControl skin : skins) {
                Set<String> paths = new HashSet<>();
                for (Joint joint : skin.getArmature().getJointList()) {
                    String path = path(joint);
                    if (paths.add(path)) {
                        joints.computeIfAbsent(path, ignored -> new ArrayList<>()).add(joint);
                    } else {
                        ambiguousJoints.add(path);
                    }
                }
            }
            scope.depthFirstTraversal(spatial -> nodes
                    .computeIfAbsent(path(spatial), ignored -> new ArrayList<>()).add(spatial));
        }

        private static Set<SkinningControl> rigs(Spatial node) {
            Set<SkinningControl> result = new LinkedHashSet<>();
            node.depthFirstTraversal(spatial -> {
                for (int i = 0; i < spatial.getNumControls(); i++) {
                    if (spatial.getControl(i) instanceof SkinningControl) {
                        result.add((SkinningControl) spatial.getControl(i));
                    }
                }
            });
            return result;
        }

        AnimClip bind(String id, AnimClip clip) throws IOException {
            Map<HasLocalTransform, TransformTrack> mapped = new LinkedHashMap<>();
            for (AnimTrack<?> track : clip.getTracks()) {
                if (!(track instanceof TransformTrack)) {
                    throw new IOException(id + ": unsupported external track type " + track.getClass().getSimpleName());
                }
                TransformTrack source = (TransformTrack) track;
                HasLocalTransform target = source.getTarget();
                if (target instanceof Joint) {
                    Joint joint = (Joint) target;
                    requireUnambiguous(id, path(joint));
                    List<Joint> match = joints.get(path(joint));
                    if (match == null) {
                        throw new IOException(id + ": missing joint " + path(joint));
                    }
                    for (Joint destination : match) {
                        requirePose(id, path(joint), joint.getInitialTransform(), destination.getInitialTransform());
                        mapped.put(destination, copy(source, destination));
                    }
                } else if (target instanceof Spatial) {
                    Spatial spatial = (Spatial) target;
                    List<Spatial> direct = nodes.get(path(spatial));
                    if (direct != null && direct.size() == 1) {
                        mapped.putIfAbsent(direct.get(0), copy(source, direct.get(0)));
                    }
                    // ufbx represents animation-only FBX skeletons as nodes.
                    List<Joint> match = joints.get(path(spatial));
                    if (match != null) {
                        requireUnambiguous(id, path(spatial));
                        for (Joint destination : match) {
                            requirePose(id, path(spatial), spatial.getLocalTransform(), destination.getInitialTransform());
                            mapped.putIfAbsent(destination, copy(source, destination));
                        }
                    }
                }
            }
            if (mapped.isEmpty() || (!joints.isEmpty() && mapped.keySet().stream().noneMatch(Joint.class::isInstance))) {
                throw new IOException(id + ": no compatible tracks for the selected rig");
            }
            AnimClip result = new AnimClip(id);
            result.setTracks(mapped.values().toArray(new AnimTrack<?>[0]));
            return result;
        }

        private void requireUnambiguous(String id, String path) throws IOException {
            if (ambiguousJoints.contains(path)) {
                throw new IOException(id + ": ambiguous joint path " + path);
            }
        }
    }

    private static TransformTrack copy(TransformTrack source, HasLocalTransform target) {
        return new TransformTrack(target, source.getTimes(), source.getTranslations(),
                source.getRotations(), source.getScales());
    }

    private static void requirePose(String id, String path, Transform from, Transform to) throws IOException {
        if (from.getTranslation().distanceSquared(to.getTranslation()) > 1e-6f
                || from.getScale().distanceSquared(to.getScale()) > 1e-6f
                || Math.abs(from.getRotation().dot(to.getRotation())) < 0.9999f) {
            throw new IOException(id + ": incompatible rest pose at " + path);
        }
    }

    private static String path(Joint joint) {
        Deque<String> names = new ArrayDeque<>();
        for (Joint value = joint; value != null; value = value.getParent()) {
            names.addFirst(escape(value.getName()));
        }
        return String.join("/", names);
    }

    private static String path(Spatial spatial) {
        Deque<String> names = new ArrayDeque<>();
        for (Spatial value = spatial; value.getParent() != null; value = value.getParent()) {
            names.addFirst(escape(value.getName()));
        }
        return String.join("/", names);
    }

    private static String ownerPath(Spatial root, Spatial spatial) {
        Deque<String> names = new ArrayDeque<>();
        for (Spatial value = spatial; value != root; value = value.getParent()) {
            Node parent = value.getParent();
            String name = value.getName();
            int namesBefore = 0;
            int sameName = 0;
            for (Spatial sibling : parent.getChildren()) {
                if (java.util.Objects.equals(name, sibling.getName())) {
                    if (parent.getChildIndex(sibling) < parent.getChildIndex(value)) {
                        namesBefore++;
                    }
                    sameName++;
                }
            }
            names.addFirst(escape(name) + (sameName > 1 ? ":" + namesBefore : ""));
        }
        return String.join("/", names);
    }

    private static String clipId(String asset, String owner, String name) {
        return escape(asset) + "#" + owner + "#" + escape(name);
    }

    private static String escape(String name) {
        return name == null ? "" : name.replace("%", "%25").replace("/", "%2F")
                .replace("#", "%23").replace("|", "%7C").replace(":", "%3A")
                .replace("\n", "%0A").replace("\r", "%0D").replace(" ", "%20");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

package com.jme3.anim.graph;

import com.jme3.anim.AnimComposer;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Immutable, versioned animation-controller definition. Store {@link #encode()}
 * as String user data under {@link #USER_DATA} on the spatial owning the composer.
 * J3O remains a normal model; no SDK classes are needed to load it.
 * Parameters default to their declared values; triggers must default to zero.
 * States loop by default in the editor. Non-looping states hold their final pose.
 * Transition order is priority order; one condition per edge, first match wins.
 * Exit time is minimum time in seconds since entering the source state (-1 disables).
 * See {@link AnimationGraphPlayer} for a complete game-side usage example.
 */
public final class AnimationGraph {
    public static final String USER_DATA = "jme.animationGraph.v1";
    public enum Type { FLOAT, BOOLEAN, TRIGGER }
    public enum Operator { ALWAYS, GREATER, LESS, EQUAL, TRUE, FALSE, TRIGGER }

    public static final class Parameter {
        public final String name;
        public final Type type;
        public final float initial;

        public Parameter(String name, Type type, float initial) {
            this.name = name;
            this.type = type;
            this.initial = initial;
        }
    }

    /** A clip, or an ordered 1D blend with strictly increasing thresholds. */
    public static final class State {
        public final String name;
        public final String parameter;
        public final List<String> clips;
        private final float[] thresholds;
        public final String parameterY;
        private final float[] samplesY;
        public final boolean loop;
        public final float x;
        public final float y;

        public State(String name, String parameter, List<String> clips, float[] thresholds,
                boolean loop, float x, float y) {
            this(name, parameter, "", clips, thresholds, new float[0], loop, x, y);
        }

        /** Creates a Cartesian 2D state when parameterY names a second Float parameter. */
        public State(String name, String parameter, String parameterY, List<String> clips,
                float[] thresholds, float[] samplesY, boolean loop, float x, float y) {
            this.name = name;
            this.parameter = parameter;
            this.clips = Collections.unmodifiableList(new ArrayList<>(clips));
            this.thresholds = thresholds.clone();
            this.parameterY = parameterY;
            this.samplesY = samplesY.clone();
            this.loop = loop;
            this.x = x;
            this.y = y;
        }

        public float threshold(int index) {
            return thresholds[index];
        }

        public boolean isTwoDimensional() {
            return !parameterY.isEmpty();
        }

        public float sampleY(int index) {
            return samplesY[index];
        }
    }

    /**
     * An optional looping override gated by a Boolean parameter. Each path
     * selects a joint and all its descendants, excluding all other targets.
     */
    public static final class Overlay {
        public final String parameter;
        public final String clip;
        public final float fade;
        public final List<String> joints;

        public Overlay(String parameter, String clip, float fade, List<String> joints) {
            this.parameter = parameter;
            this.clip = clip;
            this.fade = fade;
            this.joints = Collections.unmodifiableList(new ArrayList<>(joints));
        }

        /** Tests whether the joint belongs to a selected subtree. */
        public boolean includes(com.jme3.anim.Joint joint) {
            String path = jointPath(joint);
            for (String root : joints) {
                if (path.equals(root) || path.startsWith(root + "/")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns an escaped root-to-joint path for mask authoring. */
    public static String jointPath(com.jme3.anim.Joint joint) {
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (com.jme3.anim.Joint current = joint; current != null; current = current.getParent()) {
            String name = current.getName();
            parts.addFirst(name == null ? "" : name.replace("%", "%25").replace("/", "%2F"));
        }
        return String.join("/", parts);
    }

    public static final class Transition {
        public final String from;
        public final String to;
        public final String parameter;
        public final Operator operator;
        public final float value;
        public final float duration;
        public final float exitTime;

        public Transition(String from, String to, String parameter, Operator operator,
                float value, float duration, float exitTime) {
            this.from = from;
            this.to = to;
            this.parameter = parameter;
            this.operator = operator;
            this.value = value;
            this.duration = duration;
            this.exitTime = exitTime;
        }
    }

    public final String entry;
    public final List<Parameter> parameters;
    public final List<State> states;
    public final List<Transition> transitions;
    public final Overlay overlay;
    public final RootMotion rootMotion;

    /** Authored root-motion source. Null on graphs that use ordinary playback. */
    public static final class RootMotion {
        public final String path;
        public final boolean joint;
        public final boolean autoApply;

        /**
         * @param path stable joint path or node path relative to the composer owner
         * @param joint true for a skeletal joint, false for a scene node
         * @param autoApply true to move the non-physics composer owner automatically
         */
        public RootMotion(String path, boolean joint, boolean autoApply) {
            this.path = java.util.Objects.requireNonNull(path, "path");
            this.joint = joint;
            this.autoApply = autoApply;
        }

        /** Resolves a transform track target without depending on its object identity. */
        boolean includes(com.jme3.anim.util.HasLocalTransform target, com.jme3.scene.Spatial owner) {
            if (joint) {
                return target instanceof com.jme3.anim.Joint
                        && jointPath((com.jme3.anim.Joint) target).equals(path);
            }
            return target instanceof com.jme3.scene.Spatial
                    && path.equals(nodePath(owner, (com.jme3.scene.Spatial) target));
        }
    }

    /** Returns the owner-relative path, "." for the owner, or null for outside targets. */
    public static String nodePath(com.jme3.scene.Spatial owner, com.jme3.scene.Spatial target) {
        if (owner == null) {
            return null;
        }
        if (owner == target) {
            return ".";
        }
        java.util.Deque<String> parts = new java.util.ArrayDeque<>();
        for (com.jme3.scene.Spatial node = target; node != owner; node = node.getParent()) {
            if (node == null) {
                return null;
            }
            String name = node.getName();
            String piece = name == null ? "" : name.replace("%", "%25").replace("/", "%2F")
                    .replace(".", "%2E").replace(":", "%3A");
            int ordinal = 0;
            if (node.getParent() != null) {
                for (com.jme3.scene.Spatial sibling : node.getParent().getChildren()) {
                    if (sibling == node) {
                        break;
                    }
                    if (java.util.Objects.equals(name, sibling.getName())) {
                        ++ordinal;
                    }
                }
            }
            parts.addFirst(piece + (ordinal > 0 ? ":" + ordinal : ""));
        }
        return String.join("/", parts);
    }

    public AnimationGraph(String entry, List<Parameter> parameters, List<State> states,
            List<Transition> transitions) {
        this(entry, parameters, states, transitions, null);
    }

    /**
     * Creates a graph with an optional masked override.
     * <pre>{@code
     * // 第一步：声明 MoveX、MoveY 两个 FLOAT 和 Armed BOOLEAN。
     * // 第二步：用带两个参数的 State 及每个剪辑的 X/Y 坐标建立移动状态。
     * // 第三步：用 jointPath(chest) 定义遮罩，添加 HoldGun 覆盖。
     * // 第四步：保存 encode() 到 USER_DATA，用 AnimationGraphPlayer.attach 启动。
     * // 第五步：setBoolean("Armed", false) 淡出覆盖；传 null 构造无覆盖的图。
     * }</pre>
     */
    public AnimationGraph(String entry, List<Parameter> parameters, List<State> states,
            List<Transition> transitions, Overlay overlay) {
        this(entry, parameters, states, transitions, overlay, null);
    }

    /**
     * Creates a graph with optional root motion.
     * <pre>{@code
     * // 第一步：用 jointPath(rootJoint) 或 nodePath(owner, rootNode) 选择源。
     * // 第二步：用 new RootMotion(path, true, true) 启用自动应用并保存图到 J3O。
     * // 第三步：加载并挂到场景，第一次逻辑更新自动启动图。
     * // 第四步：物理角色选 autoApply=false，用 attach(owner) 提前装入位移消费回调。
     * // 第五步：传 null 并重新保存可恢复普通播放。
     * }</pre>
     * Root motion uses XZ translation and Y yaw of a Y-up source. Static parent
     * scale and orientation are taken into account. Vertical movement, tilt
     * and scale stay in the pose.
     */
    public AnimationGraph(String entry, List<Parameter> parameters, List<State> states,
            List<Transition> transitions, Overlay overlay, RootMotion rootMotion) {
        this.entry = entry;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.states = Collections.unmodifiableList(new ArrayList<>(states));
        this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
        this.overlay = overlay;
        this.rootMotion = rootMotion;
        validate(null);
    }

    /** Validate references and types; optionally validate clips against a composer. */
    public void validate(AnimComposer composer) {
        Map<String, Parameter> params = new LinkedHashMap<>();
        for (Parameter p : parameters) {
            require(p.name != null && !p.name.trim().isEmpty() && p.type != null, "Invalid parameter");
            require(params.put(p.name, p) == null, "Duplicate parameter: " + p.name);
            require(Float.isFinite(p.initial), "Non-finite default: " + p.name);
            require(p.type == Type.FLOAT || p.initial == 0 || (p.type == Type.BOOLEAN && p.initial == 1),
                    "Boolean defaults must be 0/1; triggers must be 0");
        }
        Map<String, State> names = new LinkedHashMap<>();
        for (State s : states) {
            require(s.name != null && !s.name.trim().isEmpty(), "Empty state name");
            require(names.put(s.name, s) == null, "Duplicate state: " + s.name);
            require(!s.clips.isEmpty() && s.clips.size() == s.thresholds.length, "Clip/threshold count: " + s.name);
            require(Float.isFinite(s.x) && Float.isFinite(s.y), "Invalid node position");
            if (s.clips.size() > 1) {
                require(params.containsKey(s.parameter) && params.get(s.parameter).type == Type.FLOAT,
                        "Blend requires a float parameter: " + s.name);
            }
            if (s.isTwoDimensional()) {
                require(s.clips.size() >= 2 && s.clips.size() <= 64 && s.samplesY.length == s.clips.size()
                        && !s.parameter.equals(s.parameterY) && params.containsKey(s.parameterY)
                        && params.get(s.parameterY).type == Type.FLOAT,
                        "2D blend requires two different Float parameters and 2 to 64 samples: " + s.name);
            }
            for (int i = 0; i < s.clips.size(); i++) {
                require(s.clips.get(i) != null && !s.clips.get(i).isEmpty(), "Empty clip");
                if (s.isTwoDimensional()) {
                    require(Float.isFinite(s.thresholds[i]) && Float.isFinite(s.samplesY[i]),
                            "Non-finite 2D sample: " + s.name);
                    for (int j = 0; j < i; ++j) {
                        require(s.thresholds[j] != s.thresholds[i] || s.samplesY[j] != s.samplesY[i],
                                "Duplicate 2D sample: " + s.name);
                    }
                } else {
                    require(Float.isFinite(s.thresholds[i]) && (i == 0 || s.thresholds[i] > s.thresholds[i - 1]),
                            "Thresholds must increase: " + s.name);
                }
                if (composer != null) {
                    require(composer.hasAnimClip(s.clips.get(i)), "Missing clip: " + s.clips.get(i));
                    double length = composer.getAnimClip(s.clips.get(i)).getLength();
                    // A single-frame clip holds its pose at time zero.
                    require(Double.isFinite(length) && length >= 0,
                            "Invalid clip duration: " + s.clips.get(i));
                }
            }
        }
        require(names.containsKey(entry), "Entry state does not exist: " + entry);
        if (rootMotion != null) {
            require(rootMotion.joint || !rootMotion.autoApply || !".".equals(rootMotion.path),
                    "Automatic root motion requires a joint or a child node");
            if (composer != null) {
                require(composer.getSpatial() != null, "Root motion requires a composer attached to a model");
                require(states.stream().flatMap(state -> state.clips.stream()).distinct()
                        .flatMap(clip -> java.util.Arrays.stream(composer.getAnimClip(clip).getTracks()))
                        .anyMatch(track -> track instanceof com.jme3.anim.TransformTrack
                                && rootMotion.includes(((com.jme3.anim.TransformTrack) track).getTarget(), composer.getSpatial())),
                        "Root motion track does not exist in a base clip: " + rootMotion.path);
            }
        }
        if (overlay != null) {
            require(params.containsKey(overlay.parameter) && params.get(overlay.parameter).type == Type.BOOLEAN,
                    "Overlay requires a Boolean parameter");
            require(overlay.clip != null && !overlay.clip.isEmpty() && Float.isFinite(overlay.fade)
                    && overlay.fade >= 0 && !overlay.joints.isEmpty(), "Invalid animation overlay");
            if (composer != null) {
                require(composer.hasAnimClip(overlay.clip), "Missing clip: " + overlay.clip);
                require(composer.getAnimClip(overlay.clip).getLength() >= 0
                        && Double.isFinite(composer.getAnimClip(overlay.clip).getLength()),
                        "Invalid clip duration: " + overlay.clip);
                require(java.util.Arrays.stream(composer.getAnimClip(overlay.clip).getTracks())
                        .anyMatch(this::overlayIncludes), "Overlay mask has no animated joints: " + overlay.clip);
            }
        }
        for (Transition t : transitions) {
            require(names.containsKey(t.from) && names.containsKey(t.to), "Unknown transition state");
            require(t.operator != null && Float.isFinite(t.duration) && t.duration >= 0
                    && Float.isFinite(t.exitTime) && t.exitTime >= -1 && Float.isFinite(t.value), "Invalid transition timing/value");
            if (t.operator != Operator.ALWAYS) {
                Parameter p = params.get(t.parameter);
                require(p != null, "Unknown parameter: " + t.parameter);
                Type expected = t.operator == Operator.TRIGGER ? Type.TRIGGER
                        : t.operator == Operator.TRUE || t.operator == Operator.FALSE ? Type.BOOLEAN : Type.FLOAT;
                require(p.type == expected, "Condition type mismatch: " + t.parameter);
            }
        }
    }

    boolean overlayIncludes(com.jme3.anim.AnimTrack<?> track) {
        if (overlay == null || !(track instanceof com.jme3.anim.TransformTrack)
                || !(((com.jme3.anim.TransformTrack) track).getTarget() instanceof com.jme3.anim.Joint)) {
            return false;
        }
        return overlay.includes((com.jme3.anim.Joint) ((com.jme3.anim.TransformTrack) track).getTarget());
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Encode portable String user data, including editor node positions. */
    public String encode() {
        Properties p = new Properties();
        p.setProperty("version", rootMotion != null ? "3"
                : overlay != null || states.stream().anyMatch(State::isTwoDimensional) ? "2" : "1");
        if (rootMotion != null) {
            p.setProperty("rootMotion.path", rootMotion.path);
            p.setProperty("rootMotion.joint", Boolean.toString(rootMotion.joint));
            p.setProperty("rootMotion.auto", Boolean.toString(rootMotion.autoApply));
        }
        p.setProperty("entry", entry);
        p.setProperty("parameters", Integer.toString(parameters.size()));
        p.setProperty("states", Integer.toString(states.size()));
        p.setProperty("transitions", Integer.toString(transitions.size()));
        for (int i = 0; i < parameters.size(); i++) {
            Parameter a = parameters.get(i);
            put(p, "p." + i, a.name, a.type.name(), a.initial);
        }
        for (int i = 0; i < states.size(); i++) {
            State s = states.get(i);
            put(p, "s." + i, s.name, s.parameter, s.loop, s.x, s.y, s.clips.size());
            for (int j = 0; j < s.clips.size(); j++) {
                put(p, "s." + i + ".c." + j, s.clips.get(j), s.threshold(j));
                if (s.isTwoDimensional()) {
                    p.setProperty("s." + i + ".c." + j + ".2", Float.toString(s.sampleY(j)));
                }
            }
            if (s.isTwoDimensional()) {
                p.setProperty("s." + i + ".6", s.parameterY);
            }
        }
        if (overlay != null) {
            put(p, "overlay", overlay.parameter, overlay.clip, overlay.fade, overlay.joints.size());
            put(p, "overlay.joint", overlay.joints.toArray());
        }
        for (int i = 0; i < transitions.size(); i++) {
            Transition t = transitions.get(i);
            put(p, "t." + i, t.from, t.to, t.parameter, t.operator.name(), t.value, t.duration, t.exitTime);
        }
        try {
            StringWriter writer = new StringWriter();
            p.store(writer, "JME Animation Graph");
            // Properties.store emits a timestamp; omit it for stable undo/save comparisons.
            return writer.toString().replaceAll("(?m)^#.*\\r?\\n", "");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void put(Properties p, String prefix, Object... values) {
        for (int i = 0; i < values.length; i++) {
            p.setProperty(prefix + "." + i, String.valueOf(values[i]));
        }
    }

    /** Decode and validate a definition. Unknown versions fail rather than losing data. */
    public static AnimationGraph decode(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid animation graph", exception);
        }
        String version = p.getProperty("version");
        require("1".equals(version) || "2".equals(version) || "3".equals(version), "Unsupported animation graph version");
        List<Parameter> parameters = new ArrayList<>();
        List<State> states = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();
        for (int i = 0; i < count(p, "parameters"); i++) {
            String k = "p." + i + ".";
            parameters.add(new Parameter(p.getProperty(k + 0), Type.valueOf(p.getProperty(k + 1)), number(p, k + 2)));
        }
        for (int i = 0; i < count(p, "states"); i++) {
            String k = "s." + i + ".";
            List<String> clips = new ArrayList<>();
            float[] thresholds = new float[count(p, k + 5)];
            String parameterY = p.getProperty(k + 6, "");
            float[] samplesY = parameterY.isEmpty() ? new float[0] : new float[thresholds.length];
            for (int j = 0; j < thresholds.length; j++) {
                clips.add(p.getProperty(k + "c." + j + ".0"));
                thresholds[j] = number(p, k + "c." + j + ".1");
                if (!parameterY.isEmpty()) {
                    samplesY[j] = number(p, k + "c." + j + ".2");
                }
            }
            require("true".equals(p.getProperty(k + 2)) || "false".equals(p.getProperty(k + 2)), "Invalid loop flag");
            states.add(new State(p.getProperty(k + 0), p.getProperty(k + 1), parameterY, clips, thresholds, samplesY,
                    Boolean.parseBoolean(p.getProperty(k + 2)), number(p, k + 3), number(p, k + 4)));
        }
        for (int i = 0; i < count(p, "transitions"); i++) {
            String k = "t." + i + ".";
            transitions.add(new Transition(p.getProperty(k + 0), p.getProperty(k + 1), p.getProperty(k + 2),
                    Operator.valueOf(p.getProperty(k + 3)), number(p, k + 4), number(p, k + 5), number(p, k + 6)));
        }
        Overlay overlay = null;
        if (p.containsKey("overlay.0")) {
            List<String> joints = new ArrayList<>();
            for (int i = 0; i < count(p, "overlay.3"); ++i) {
                joints.add(p.getProperty("overlay.joint." + i));
            }
            overlay = new Overlay(p.getProperty("overlay.0"), p.getProperty("overlay.1"), number(p, "overlay.2"), joints);
        }
        RootMotion rootMotion = null;
        if (p.containsKey("rootMotion.path")) {
            rootMotion = new RootMotion(p.getProperty("rootMotion.path"),
                    Boolean.parseBoolean(p.getProperty("rootMotion.joint")),
                    Boolean.parseBoolean(p.getProperty("rootMotion.auto")));
        }
        return new AnimationGraph(p.getProperty("entry"), parameters, states, transitions, overlay, rootMotion);
    }

    private static int count(Properties p, String key) {
        int count = Integer.parseInt(p.getProperty(key));
        require(count >= 0 && count <= 10000, "Invalid count: " + key);
        return count;
    }

    private static float number(Properties p, String key) {
        return Float.parseFloat(p.getProperty(key));
    }
}

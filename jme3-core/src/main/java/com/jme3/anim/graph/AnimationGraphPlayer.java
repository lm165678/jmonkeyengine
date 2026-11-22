package com.jme3.anim.graph;

import com.jme3.anim.*;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.anim.tween.action.Action;
import com.jme3.anim.util.HasLocalTransform;
import com.jme3.math.Transform;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays a character's graph through the AnimComposer default layer.
 * It executes inside AnimComposer, before the normal skinning update. Other layers
 * are untouched. Create a fresh player after loading/cloning each character;
 * player state is deliberately not serialized. The definition is String user data.
 * All methods belong to the application update thread (enqueue UI/network input).
 * <p>Transitions crossfade two advancing poses and cannot be interrupted in v1.
 * Conditions are evaluated once per update, in definition order. Triggers persist
 * until consumed by a winning edge or explicitly reset. Exit times and fades use
 * composer-scaled seconds; negative playback is unsupported. 1D blends clamp at
 * endpoints, blend adjacent samples, and synchronize normalized phase using the
 * weighted clip duration. Phase advances continuously as weights change; a fully
 * weighted clip plays at its native rate. Missing tracks use the pose captured at construction.
 * Zero-duration clips (for example a single-frame T-pose) hold their time-zero
 * pose, with or without looping. They can blend with moving clips and leave via
 * the same elapsed-time/parameter transitions; no synthetic duration is added.
 * Transform and morph tracks are supported; unknown track types fail validation.
 * Root motion remains ordinary transform animation, without physics extraction.
 * <pre>{@code
 * // 第一步：加载模型，找到直接持有 AnimComposer 的节点。
 * Spatial character = assetManager.loadModel("Models/Hero.j3o");
 * character.depthFirstTraversal(owner -> {
 *     String definition = owner.getUserData(AnimationGraph.USER_DATA);
 *     if (definition != null && owner.getControl(AnimComposer.class) != null) {
 *         // 第二步：为每个角色建立独立实例，在游戏更新线程启动。
 *         AnimationGraphPlayer player = AnimationGraphPlayer.attach(owner);
 *         // 第三步：按编辑器中声明的参数名传入游戏状态。
 *         player.setFloat("Speed", 2f);
 *         // player.setBoolean("Grounded", true);
 *         // player.trigger("Attack");
 *         // 第四步：不用时在更新线程关闭，恢复原动作、时间和初始姿态。
 *         // player.close();
 *     }
 * });
 * rootNode.attachChild(character);
 * }</pre>
 * By default attach starts the entry state with declared parameter defaults.
 * Missing/invalid definitions throw; they never silently replace the model's data.
 */
public final class AnimationGraphPlayer extends Action implements AutoCloseable {
    private final AnimationGraph graph;
    private final AnimComposer composer;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Parameter> declarations = new LinkedHashMap<>();
    private final Map<String, Float> values = new LinkedHashMap<>();
    private final Pose baseline = new Pose();
    private final List<TransformTrack> overlayTracks = new java.util.ArrayList<>();
    private final Map<String, MotionTrack> rootTracks = new LinkedHashMap<>();
    private final Map<HasLocalTransform, Transform> rootTargets = new LinkedHashMap<>();
    private java.util.function.Consumer<RootMotionDelta> rootMotionConsumer;
    private RootMotionDelta rootMotionDelta = RootMotionDelta.ZERO;
    private double overlayTime;
    private float overlayWeight;
    private State current;
    private State outgoing;
    private double time;
    private double outgoingTime;
    private double phase;
    private double outgoingPhase;
    private double previousInput;
    private double fadeTime;
    private double fadeDuration;
    private boolean paused;
    private boolean closed;
    private Action previousAction;
    private String previousName;
    private double previousTime;
    private boolean previousLoop;
    private Object previousManager;

    /** Validate before taking ownership; use {@link #attach(Spatial)} for stored graphs. */
    public AnimationGraphPlayer(AnimationGraph graph, AnimComposer composer) {
        this.graph = graph;
        this.composer = composer;
        graph.validate(composer);
        for (Parameter p : graph.parameters) {
            declarations.put(p.name, p);
            values.put(p.name, p.initial);
        }
        for (State s : graph.states) {
            states.put(s.name, s);
            for (String clip : s.clips) {
                for (AnimTrack<?> track : composer.getAnimClip(clip).getTracks()) {
                    if (track instanceof TransformTrack) {
                        HasLocalTransform target = ((TransformTrack) track).getTarget();
                        baseline.transforms.put(target, target.getLocalTransform().clone());
                        if (graph.rootMotion != null && graph.rootMotion.includes(target, composer.getSpatial())) {
                            Transform rest = baseline.transforms.get(target);
                            if (!rootTracks.containsKey(clip)) {
                                rootTracks.put(clip, new MotionTrack((TransformTrack) track, rest,
                                        motionFrame(target)));
                            }
                            rootTargets.putIfAbsent(target, rest);
                        }
                    } else if (track instanceof MorphTrack) {
                        MorphTrack morph = (MorphTrack) track;
                        if (morph.getTarget().getMorphState().length != morph.getNbMorphTargets()) {
                            throw new IllegalArgumentException("Morph track/mesh target count mismatch: " + clip);
                        }
                        baseline.morphs.put(morph.getTarget(), morph.getTarget().getMorphState().clone());
                    } else {
                        throw new IllegalArgumentException("Unsupported animation track: " + track.getClass().getName());
                    }
                }
            }
        }
        if (graph.overlay != null) {
            for (AnimTrack<?> track : composer.getAnimClip(graph.overlay.clip).getTracks()) {
                if (graph.overlayIncludes(track)) {
                    TransformTrack transform = (TransformTrack) track;
                    HasLocalTransform target = transform.getTarget();
                    baseline.transforms.putIfAbsent(target, target.getLocalTransform().clone());
                    overlayTracks.add(transform);
                }
            }
        }
        current = states.get(graph.entry);
        setLength(Double.MAX_VALUE);
        AnimLayer layer = composer.getLayer(AnimComposer.DEFAULT_LAYER);
        if (layer.getCurrentAction() instanceof AnimationGraphPlayer) {
            throw new IllegalStateException("Close the existing graph player first");
        }
        previousAction = layer.getCurrentAction();
        previousName = layer.getCurrentActionName();
        previousTime = layer.getTime();
        previousLoop = layer.isLooping();
        previousManager = layer.getManager();
        layer.setCurrentAction("Animation graph", this, true);
        layer.setManager(this);
        if (graph.rootMotion != null && graph.rootMotion.autoApply) {
            Spatial actor = composer.getSpatial();
            rootMotionConsumer = delta -> delta.applyTo(actor);
        }
        resetPose();
    }

    /** Start the graph stored on the spatial directly owning the composer. */
    public static AnimationGraphPlayer attach(Spatial owner) {
        String text = owner.getUserData(AnimationGraph.USER_DATA);
        AnimComposer composer = owner.getControl(AnimComposer.class);
        if (text == null || composer == null) {
            throw new IllegalArgumentException("Spatial needs an animation graph and AnimComposer");
        }
        if (composer.getCurrentAction() instanceof AnimationGraphPlayer) {
            return (AnimationGraphPlayer) composer.getCurrentAction();
        }
        return new AnimationGraphPlayer(AnimationGraph.decode(text), composer);
    }

    /**
     * Starts an opted-in, stored graph on its owning composer. Ordinary graphs
     * and an already selected action retain their previous startup behavior.
     *
     * @param composer attached composer on its first logical update
     */
    public static void startStoredRootMotion(AnimComposer composer) {
        Spatial owner = composer.getSpatial();
        if (owner == null || composer.getCurrentAction() != null) {
            return;
        }
        Object stored = owner.getUserData(AnimationGraph.USER_DATA);
        if (stored instanceof String) {
            String text = (String) stored;
            if (text.startsWith("rootMotion.path=") || text.contains("\nrootMotion.path=")) {
                attach(owner);
            }
        }
    }

    /**
     * Replaces the mover (including automatic movement). A null consumer
     * disables applying movement while keeping extraction enabled.
     * Call on the owning scene thread, once after attach or when changing modes.
     *
     * @param consumer receives one delta per active animation update
     */
    public void setRootMotionConsumer(java.util.function.Consumer<RootMotionDelta> consumer) {
        rootMotionConsumer = consumer;
    }

    /** @return the most recent logical update's delta, or zero while paused or reset */
    public RootMotionDelta getRootMotionDelta() {
        return rootMotionDelta;
    }

    /** @return whether this graph extracts forward-playing root motion */
    public boolean hasRootMotion() {
        return graph.rootMotion != null;
    }

    /**
     * Rebases the layer clock after an explicit seek so changing the clock does
     * not move the actor. The graph's state clock remains independent.
     *
     * @param seconds the new layer clock time
     */
    public void synchronizeLayerClock(double seconds) {
        if (graph.rootMotion != null && Double.isFinite(seconds)) {
            previousInput = seconds;
            rootMotionDelta = RootMotionDelta.ZERO;
        }
    }

    public void setFloat(String name, float value) {
        set(name, Type.FLOAT, value);
    }

    public void setBoolean(String name, boolean value) {
        set(name, Type.BOOLEAN, value ? 1 : 0);
    }

    public void trigger(String name) {
        set(name, Type.TRIGGER, 1);
    }

    public void resetTrigger(String name) {
        set(name, Type.TRIGGER, 0);
    }

    private void set(String name, Type type, float value) {
        Parameter p = declarations.get(name);
        if (closed || p == null || p.type != type || !Float.isFinite(value)) {
            throw new IllegalArgumentException("Invalid parameter assignment: " + name);
        }
        values.put(name, value);
    }

    public String getStateName() {
        return current.name;
    }

    public double getStateTime() {
        return time;
    }

    public boolean isTransitioning() {
        return outgoing != null;
    }

    /** Pause the graph clock, keeping the current pose. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            rootMotionDelta = RootMotionDelta.ZERO;
        }
    }

    /** Reset entry state and all parameters, including pending triggers. */
    public void restart() {
        current = states.get(graph.entry);
        outgoing = null;
        time = 0;
        phase = 0;
        for (Parameter p : graph.parameters) {
            values.put(p.name, p.initial);
        }
        resetPose();
    }

    private void resetPose() {
        rootMotionDelta = RootMotionDelta.ZERO;
        overlayTime = 0;
        overlayWeight = overlayEnabled() ? 1 : 0;
        Pose pose = sample(current, 0);
        applyOverlay(pose);
        stripRootMotion(pose);
        apply(pose);
    }

    private boolean overlayEnabled() {
        return graph.overlay != null && values.get(graph.overlay.parameter) != 0f;
    }

    private void applyOverlay(Pose pose) {
        if (overlayWeight <= 0) {
            return;
        }
        double length = composer.getAnimClip(graph.overlay.clip).getLength();
        double position = length > 0 ? overlayTime % length : 0;
        Transform sample = new Transform();
        for (TransformTrack track : overlayTracks) {
            Transform base = pose.transforms.get(track.getTarget());
            sample.set(base);
            track.getDataAtTime(position, sample);
            base.interpolateTransforms(base, sample, overlayWeight);
        }
    }

    @Override
    public boolean interpolate(double input) {
        double delta = Math.max(0, input - previousInput);
        previousInput = input;
        rootMotionDelta = RootMotionDelta.ZERO;
        if (closed || paused) {
            return true;
        }
        if (delta > 0 && graph.rootMotion != null) {
            rootMotionDelta = stepMotion(delta);
        }
        time += delta;
        phase += phaseStep(current, delta);
        if (outgoing != null) {
            outgoingTime += delta;
            outgoingPhase += phaseStep(outgoing, delta);
            fadeTime += delta;
            if (fadeTime >= fadeDuration) {
                outgoing = null;
            }
        } else {
            for (Transition edge : graph.transitions) {
                if (edge.from.equals(current.name) && time >= edge.exitTime && matches(edge)) {
                    outgoing = edge.duration > 0 ? current : null;
                    outgoingTime = time;
                    outgoingPhase = phase;
                    current = states.get(edge.to);
                    time = 0;
                    phase = 0;
                    fadeTime = 0;
                    fadeDuration = edge.duration;
                    if (edge.operator == Operator.TRIGGER) {
                        values.put(edge.parameter, 0f);
                    }
                    break;
                }
            }
        }
        Pose pose = sample(current, phase);
        if (outgoing != null) {
            Pose source = sample(outgoing, outgoingPhase);
            source.blend(pose, (float) (fadeTime / fadeDuration));
            pose = source;
        }
        if (graph.overlay != null) {
            boolean enabled = overlayEnabled();
            if (enabled || overlayWeight > 0) {
                overlayTime += delta;
            }
            float step = graph.overlay.fade == 0 ? 1 : (float) Math.min(1, delta / graph.overlay.fade);
            overlayWeight = enabled ? Math.min(1, overlayWeight + step) : Math.max(0, overlayWeight - step);
            if (!enabled && overlayWeight == 0) {
                overlayTime = 0;
            }
            applyOverlay(pose);
        }
        stripRootMotion(pose);
        apply(pose);
        if (rootMotionConsumer != null && delta > 0 && graph.rootMotion != null) {
            rootMotionConsumer.accept(rootMotionDelta);
        }
        return true;
    }

    private boolean matches(Transition t) {
        if (t.operator == Operator.ALWAYS) {
            return true;
        }
        float v = values.get(t.parameter);
        switch (t.operator) {
            case GREATER:
                return v > t.value;
            case LESS:
                return v < t.value;
            case EQUAL:
                return v == t.value;
            case TRUE:
            case TRIGGER:
                return v != 0;
            case FALSE:
                return v == 0;
            default:
                return false;
        }
    }

    private double stateLength(State state) {
        double length = 0;
        double total = 0;
        double[] weights = weights(state);
        for (int i = 0; i < weights.length; i++) {
            length += weights[i] * composer.getAnimClip(state.clips.get(i)).getLength();
            total += weights[i];
        }
        return total > 0 ? length / total : 0;
    }

    private double phaseStep(State state, double seconds) {
        double length = stateLength(state);
        return length > 0 ? seconds / length : 0;
    }

    private Pose sample(State state, double cycles) {
        // A zero-duration state holds its first pose, including looping T-poses.
        // Transition exit times still use elapsed seconds, not normalized phase.
        double phase = state.loop ? cycles % 1 : Math.min(1, cycles);
        double[] weights = weights(state);
        double total = 0;
        Pose pose = null;
        for (int i = 0; i < weights.length; ++i) {
            if (weights[i] > 0) {
                Pose next = sampleClip(state.clips.get(i), phase);
                total += weights[i];
                if (pose == null) {
                    pose = next;
                } else {
                    pose.blend(next, (float) (weights[i] / total));
                }
            }
        }
        return pose;
    }

    private double[] weights(State state) {
        double[] weights = new double[state.clips.size()];
        if (state.isTwoDimensional()) {
            cartesianWeights(state, weights);
            return weights;
        }
        float value = state.clips.size() == 1 ? state.threshold(0) : values.get(state.parameter);
        int lower = 0;
        while (lower + 1 < state.clips.size() && value >= state.threshold(lower + 1)) {
            lower++;
        }
        weights[lower] = 1;
        if (lower + 1 < state.clips.size() && value > state.threshold(lower)) {
            float weight = (value - state.threshold(lower)) / (state.threshold(lower + 1) - state.threshold(lower));
            weights[lower] = 1 - weight;
            weights[lower + 1] = weight;
        }
        return weights;
    }

    private void cartesianWeights(State state, double[] weights) {
        double x = values.get(state.parameter);
        double y = values.get(state.parameterY);
        double nearestDistance = Double.POSITIVE_INFINITY;
        int nearest = 0;
        boolean found = false;
        for (int i = 0; i < state.clips.size(); ++i) {
            double dx = x - state.threshold(i);
            double dy = y - state.sampleY(i);
            double distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
            double weight = 1;
            // Fade each sample towards every other sample's perpendicular band.
            for (int j = 0; j < state.clips.size() && weight > 0; ++j) {
                if (i != j) {
                    double sx = (double) state.threshold(j) - state.threshold(i);
                    double sy = (double) state.sampleY(j) - state.sampleY(i);
                    weight = Math.min(weight, 1 - (dx * sx + dy * sy) / (sx * sx + sy * sy));
                }
            }
            if (weight > 0) {
                weights[i] = weight;
                found = true;
            }
        }
        if (!found) {
            weights[nearest] = 1;
        }
    }

    private RootMotionDelta stepMotion(double delta) {
        if (outgoing == null) {
            // A transition chosen at the end of this tick starts the next interval.
            return stateMotion(current, time, time + delta);
        }
        double shared = Math.min(delta, Math.max(0, fadeDuration - fadeTime));
        double newWeight = Math.min(1, (fadeTime + shared * 0.5) / fadeDuration);
        RootMotionDelta fade = RootMotionDelta.ZERO
                .plusWeighted(stateMotion(outgoing, outgoingTime, outgoingTime + shared), 1 - newWeight)
                .plusWeighted(stateMotion(current, time, time + shared), newWeight);
        return fade.then(stateMotion(current, time + shared, time + delta));
    }

    private RootMotionDelta stateMotion(State state, double from, double to) {
        double length = stateLength(state);
        if (length <= 0 || to <= from) {
            return RootMotionDelta.ZERO;
        }
        double basePhase = state == current ? phase : outgoingPhase;
        double baseTime = state == current ? time : outgoingTime;
        double fromPhase = basePhase + (from - baseTime) / length;
        double toPhase = basePhase + (to - baseTime) / length;
        double[] weights = weights(state);
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        RootMotionDelta result = RootMotionDelta.ZERO;
        for (int i = 0; i < weights.length; ++i) {
            String name = state.clips.get(i);
            MotionTrack track = rootTracks.get(name);
            double clipLength = composer.getAnimClip(name).getLength();
            if (weights[i] > 0 && track != null && clipLength > 0) {
                RootMotionDelta a = accumulatedMotion(track, clipLength, fromPhase, state.loop);
                RootMotionDelta b = accumulatedMotion(track, clipLength, toPhase, state.loop);
                result = result.plusWeighted(track.inActorSpace(a.inverse().then(b)), weights[i] / total);
            }
        }
        return result;
    }

    private Transform motionFrame(HasLocalTransform target) {
        Transform frame = new Transform();
        Spatial owner = composer.getSpatial();
        if (target instanceof Spatial) {
            if (target != owner) {
                appendParents(frame, ((Spatial) target).getParent(), owner);
            }
        } else if (target instanceof Joint) {
            Joint joint = (Joint) target;
            for (Joint parent = joint.getParent(); parent != null; parent = parent.getParent()) {
                frame.combineWithParent(parent.getInitialTransform());
            }
            Spatial[] skinOwner = {null};
            owner.depthFirstTraversal(spatial -> {
                if (skinOwner[0] == null) {
                    for (int i = 0; i < spatial.getNumControls(); ++i) {
                        if (spatial.getControl(i) instanceof SkinningControl) {
                            SkinningControl skin = (SkinningControl) spatial.getControl(i);
                            if (skin.getArmature().getJointList().contains(joint)) {
                                skinOwner[0] = spatial;
                                return;
                            }
                        }
                    }
                }
            });
            if (skinOwner[0] != null) {
                appendParents(frame, skinOwner[0], owner);
            }
        }
        return frame;
    }

    private static void appendParents(Transform frame, Spatial start, Spatial owner) {
        for (Spatial spatial = start; spatial != null && spatial != owner; spatial = spatial.getParent()) {
            frame.combineWithParent(spatial.getLocalTransform());
        }
    }

    private RootMotionDelta accumulatedMotion(MotionTrack track, double duration, double cycles, boolean loop) {
        if (!loop) {
            return track.origin.then(track.sample(Math.min(1, cycles) * duration));
        }
        long whole = (long) Math.floor(cycles);
        RootMotionDelta phase = track.origin.then(track.sample((cycles - whole) * duration));
        return whole == 0 ? phase
                : track.origin.then(track.sample(duration)).repeated(whole).then(phase);
    }

    private static final class MotionTrack {
        private final TransformTrack track;
        private final Transform rest;
        private final Transform frame;
        private final float yawDirection;
        private final float[] keys;
        private final double[] unwrappedYaw;
        private final RootMotionDelta origin;

        MotionTrack(TransformTrack track, Transform rest, Transform frame) {
            this.track = track;
            this.rest = rest;
            this.frame = frame;
            yawDirection = frame.getRotation().mult(Vector3f.UNIT_Y).y;
            keys = track.getTimes();
            Quaternion[] rotations = track.getRotations();
            unwrappedYaw = rotations == null ? new double[0] : new double[rotations.length];
            for (int i = 0; i < unwrappedYaw.length; ++i) {
                double current = yaw(rotations[i]);
                unwrappedYaw[i] = i == 0 ? current
                        : unwrappedYaw[i - 1] + wrapYaw(current - unwrappedYaw[i - 1]);
            }
            origin = sample(0).inverse();
        }

        RootMotionDelta inActorSpace(RootMotionDelta value) {
            Vector3f movement = frame.getRotation().mult(value.getTranslation().multLocal(frame.getScale()));
            return new RootMotionDelta(movement.x, movement.z, value.yaw * yawDirection);
        }

        RootMotionDelta sample(double seconds) {
            float time = (float) Math.max(keys[0], Math.min(seconds, keys[keys.length - 1]));
            Transform transform = rest.clone();
            track.getDataAtTime(time, transform);
            double sampledYaw = yaw(transform.getRotation());
            if (unwrappedYaw.length > 0) {
                int key = java.util.Arrays.binarySearch(keys, time);
                key = key >= 0 ? key : Math.max(0, -key - 2);
                sampledYaw = unwrappedYaw[key] + wrapYaw(sampledYaw - unwrappedYaw[key]);
            }
            Vector3f translation = transform.getTranslation();
            return new RootMotionDelta(translation.x, translation.z, sampledYaw);
        }
    }

    private static double yaw(Quaternion rotation) {
        return 2 * Math.atan2(rotation.getY(), rotation.getW());
    }

    private static double wrapYaw(double value) {
        return Math.atan2(Math.sin(value), Math.cos(value));
    }

    private void stripRootMotion(Pose pose) {
        Quaternion remove = new Quaternion();
        for (Map.Entry<HasLocalTransform, Transform> entry : rootTargets.entrySet()) {
            Transform transform = pose.transforms.get(entry.getKey());
            Transform rest = entry.getValue();
            transform.getTranslation().x = rest.getTranslation().x;
            transform.getTranslation().z = rest.getTranslation().z;
            Quaternion rotation = transform.getRotation();
            double inverseYaw = wrapYaw(yaw(rest.getRotation()) - yaw(rotation));
            remove.fromAngleNormalAxis((float) inverseYaw, Vector3f.UNIT_Y);
            transform.setRotation(remove.mult(rotation));
        }
    }

    private Pose sampleClip(String name, double phase) {
        Pose pose = baseline.copy();
        AnimClip clip = composer.getAnimClip(name);
        for (AnimTrack<?> track : clip.getTracks()) {
            if (track instanceof TransformTrack) {
                TransformTrack transform = (TransformTrack) track;
                transform.getDataAtTime(phase * clip.getLength(), pose.transforms.get(transform.getTarget()));
            } else if (track instanceof MorphTrack) {
                MorphTrack morph = (MorphTrack) track;
                morph.getDataAtTime(phase * clip.getLength(), pose.morphs.get(morph.getTarget()));
            }
        }
        return pose;
    }

    private void apply(Pose pose) {
        for (Map.Entry<HasLocalTransform, Transform> entry : pose.transforms.entrySet()) {
            if (graph.rootMotion != null && graph.rootMotion.autoApply && entry.getKey() == composer.getSpatial()) {
                continue;
            }
            if (getMask() == null || getMask().contains(entry.getKey())) {
                entry.getKey().setLocalTransform(entry.getValue());
            }
        }
        for (Map.Entry<Geometry, float[]> entry : pose.morphs.entrySet()) {
            entry.getKey().setMorphState(entry.getValue());
        }
    }

    /** Restores the previous action if this player still owns the layer. Safe to call again. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        AnimLayer layer = composer.getLayer(AnimComposer.DEFAULT_LAYER);
        if (layer.getCurrentAction() == this) {
            apply(baseline);
            layer.setCurrentAction(previousName, previousAction, previousLoop);
            layer.setManager(previousManager);
            if (previousAction != null && previousAction.getLength() > 0) {
                layer.setTime(previousTime);
            }
        }
    }

    private static final class Pose {
        final Map<HasLocalTransform, Transform> transforms = new LinkedHashMap<>();
        final Map<Geometry, float[]> morphs = new LinkedHashMap<>();

        Pose copy() {
            Pose result = new Pose();
            for (Map.Entry<HasLocalTransform, Transform> e : transforms.entrySet()) {
                result.transforms.put(e.getKey(), e.getValue().clone());
            }
            for (Map.Entry<Geometry, float[]> e : morphs.entrySet()) {
                result.morphs.put(e.getKey(), e.getValue().clone());
            }
            return result;
        }

        void blend(Pose other, float weight) {
            for (Map.Entry<HasLocalTransform, Transform> e : transforms.entrySet()) {
                e.getValue().interpolateTransforms(e.getValue(), other.transforms.get(e.getKey()), weight);
            }
            for (Map.Entry<Geometry, float[]> e : morphs.entrySet()) {
                float[] from = e.getValue();
                float[] to = other.morphs.get(e.getKey());
                for (int i = 0; i < from.length; i++) {
                    from[i] += (to[i] - from[i]) * weight;
                }
            }
        }
    }
}

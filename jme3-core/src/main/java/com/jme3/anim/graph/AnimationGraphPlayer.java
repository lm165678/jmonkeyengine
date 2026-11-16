package com.jme3.anim.graph;

import com.jme3.anim.*;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.anim.tween.action.Action;
import com.jme3.anim.util.HasLocalTransform;
import com.jme3.math.Transform;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-character graph player installed as the composer's default-layer action.
 * It executes inside AnimComposer, before the normal skinning update. Other layers
 * are untouched. Create a fresh player after loading/cloning each character;
 * player state is deliberately not serialized. The definition is String user data.
 * All methods belong to the application update thread (enqueue UI/network input).
 * <p>Transitions crossfade two advancing poses and cannot be interrupted in v1.
 * Conditions are evaluated once per update, in definition order. Triggers persist
 * until consumed by a winning edge or explicitly reset. Exit times and fades use
 * composer-scaled seconds; negative playback is unsupported. 1D blends clamp at
 * endpoints, blend adjacent samples, and synchronize normalized phase using the
 * longest clip's duration. Missing tracks use the pose captured at construction.
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
    private State current;
    private State outgoing;
    private double time;
    private double outgoingTime;
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
        apply(sample(current, 0));
    }

    /** Start the graph stored on the spatial directly owning the composer. */
    public static AnimationGraphPlayer attach(Spatial owner) {
        String text = owner.getUserData(AnimationGraph.USER_DATA);
        AnimComposer composer = owner.getControl(AnimComposer.class);
        if (text == null || composer == null) {
            throw new IllegalArgumentException("Spatial needs an animation graph and AnimComposer");
        }
        return new AnimationGraphPlayer(AnimationGraph.decode(text), composer);
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
    }

    /** Reset entry state and all parameters, including pending triggers. */
    public void restart() {
        current = states.get(graph.entry);
        outgoing = null;
        time = 0;
        for (Parameter p : graph.parameters) {
            values.put(p.name, p.initial);
        }
        apply(sample(current, 0));
    }

    @Override
    public boolean interpolate(double input) {
        double delta = Math.max(0, input - previousInput);
        previousInput = input;
        if (closed || paused) {
            return true;
        }
        time += delta;
        if (outgoing != null) {
            outgoingTime += delta;
            fadeTime += delta;
            if (fadeTime >= fadeDuration) {
                outgoing = null;
            }
        } else {
            for (Transition edge : graph.transitions) {
                if (edge.from.equals(current.name) && time >= edge.exitTime && matches(edge)) {
                    outgoing = edge.duration > 0 ? current : null;
                    outgoingTime = time;
                    current = states.get(edge.to);
                    time = 0;
                    fadeTime = 0;
                    fadeDuration = edge.duration;
                    if (edge.operator == Operator.TRIGGER) {
                        values.put(edge.parameter, 0f);
                    }
                    break;
                }
            }
        }
        Pose pose = sample(current, time);
        if (outgoing != null) {
            Pose source = sample(outgoing, outgoingTime);
            source.blend(pose, (float) (fadeTime / fadeDuration));
            pose = source;
        }
        apply(pose);
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

    private Pose sample(State state, double seconds) {
        double length = 0;
        for (String clip : state.clips) {
            length = Math.max(length, composer.getAnimClip(clip).getLength());
        }
        double phase = state.loop ? (seconds % length) / length : Math.min(1, seconds / length);
        float value = state.clips.size() == 1 ? state.threshold(0) : values.get(state.parameter);
        int lower = 0;
        while (lower + 1 < state.clips.size() && value >= state.threshold(lower + 1)) {
            lower++;
        }
        Pose pose = sampleClip(state.clips.get(lower), phase);
        if (lower + 1 < state.clips.size() && value > state.threshold(lower)) {
            float weight = (value - state.threshold(lower)) / (state.threshold(lower + 1) - state.threshold(lower));
            pose.blend(sampleClip(state.clips.get(lower + 1), phase), weight);
        }
        return pose;
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
            if (getMask() == null || getMask().contains(entry.getKey())) {
                entry.getKey().setLocalTransform(entry.getValue());
            }
        }
        for (Map.Entry<Geometry, float[]> entry : pose.morphs.entrySet()) {
            entry.getKey().setMorphState(entry.getValue());
        }
    }

    /** Restore the previous action only if this player still owns the layer. Idempotent. */
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

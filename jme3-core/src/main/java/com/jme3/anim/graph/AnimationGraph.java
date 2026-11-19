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
        public final boolean loop;
        public final float x;
        public final float y;

        public State(String name, String parameter, List<String> clips, float[] thresholds,
                boolean loop, float x, float y) {
            this.name = name;
            this.parameter = parameter;
            this.clips = Collections.unmodifiableList(new ArrayList<>(clips));
            this.thresholds = thresholds.clone();
            this.loop = loop;
            this.x = x;
            this.y = y;
        }

        public float threshold(int index) {
            return thresholds[index];
        }
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

    public AnimationGraph(String entry, List<Parameter> parameters, List<State> states,
            List<Transition> transitions) {
        this.entry = entry;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.states = Collections.unmodifiableList(new ArrayList<>(states));
        this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
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
            for (int i = 0; i < s.clips.size(); i++) {
                require(s.clips.get(i) != null && !s.clips.get(i).isEmpty(), "Empty clip");
                require(Float.isFinite(s.thresholds[i]) && (i == 0 || s.thresholds[i] > s.thresholds[i - 1]),
                        "Thresholds must increase: " + s.name);
                if (composer != null) {
                    require(composer.hasAnimClip(s.clips.get(i)), "Missing clip: " + s.clips.get(i));
                    double length = composer.getAnimClip(s.clips.get(i)).getLength();
                    // Single-frame poses are valid clips; sampling holds time zero.
                    require(Double.isFinite(length) && length >= 0,
                            "Invalid clip duration: " + s.clips.get(i));
                }
            }
        }
        require(names.containsKey(entry), "Entry state does not exist: " + entry);
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

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Encode portable String user data, including editor node positions. */
    public String encode() {
        Properties p = new Properties();
        p.setProperty("version", "1");
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
            }
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
        require("1".equals(p.getProperty("version")), "Unsupported animation graph version");
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
            for (int j = 0; j < thresholds.length; j++) {
                clips.add(p.getProperty(k + "c." + j + ".0"));
                thresholds[j] = number(p, k + "c." + j + ".1");
            }
            require("true".equals(p.getProperty(k + 2)) || "false".equals(p.getProperty(k + 2)), "Invalid loop flag");
            states.add(new State(p.getProperty(k + 0), p.getProperty(k + 1), clips, thresholds,
                    Boolean.parseBoolean(p.getProperty(k + 2)), number(p, k + 3), number(p, k + 4)));
        }
        for (int i = 0; i < count(p, "transitions"); i++) {
            String k = "t." + i + ".";
            transitions.add(new Transition(p.getProperty(k + 0), p.getProperty(k + 1), p.getProperty(k + 2),
                    Operator.valueOf(p.getProperty(k + 3)), number(p, k + 4), number(p, k + 5), number(p, k + 6)));
        }
        return new AnimationGraph(p.getProperty("entry"), parameters, states, transitions);
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

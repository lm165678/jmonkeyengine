package com.jme3.anim.graph;

import com.jme3.anim.*;
import com.jme3.anim.graph.AnimationGraph.*;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationGraphTest {
    private Node model() {
        Node node = new Node("character");
        AnimComposer composer = new AnimComposer();
        node.addControl(composer);
        clip(composer, node, "Idle", 0, 0, 1);
        clip(composer, node, "Walk", 10, 10, 2);
        clip(composer, node, "Run", 20, 20, 1);
        clip(composer, node, "Move", 0, 10, 1);
        return node;
    }

    private void clip(AnimComposer composer, Node target, String name, float start, float end, float length) {
        AnimClip clip = new AnimClip(name);
        clip.setTracks(new AnimTrack[]{new TransformTrack(target, new float[]{0, length},
            new Vector3f[]{new Vector3f(start, 0, 0), new Vector3f(end, 0, 0)}, null, null)});
        composer.addAnimClip(clip);
    }

    private State state(String name, boolean loop) {
        return new State(name, "", Collections.singletonList(name), new float[]{0}, loop, 0, 0);
    }

    private AnimationGraph graph(Transition... transitions) {
        return new AnimationGraph("Idle", Arrays.asList(new Parameter("Speed", Type.FLOAT, 0),
                new Parameter("Grounded", Type.BOOLEAN, 1), new Parameter("Attack", Type.TRIGGER, 0)),
                Arrays.asList(state("Idle", true), state("Walk", true), state("Run", true)), Arrays.asList(transitions));
    }

    @Test
    void timedCrossfadeAndExitTime() {
        Node node = model();
        AnimationGraph graph = graph(new Transition("Idle", "Walk", "Speed", Operator.GREATER, 1, 1, 0.5f));
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, node.getControl(AnimComposer.class));
        player.setFloat("Speed", 2);
        node.updateLogicalState(0.25f);
        assertEquals("Idle", player.getStateName());
        node.updateLogicalState(0.25f);
        assertEquals("Walk", player.getStateName());
        assertEquals(0, node.getLocalTranslation().x, 0.001);
        node.updateLogicalState(0.5f);
        assertEquals(5, node.getLocalTranslation().x, 0.001);
        node.updateLogicalState(0.5f);
        assertEquals(10, node.getLocalTranslation().x, 0.001);
        assertFalse(player.isTransitioning());
        player.close();
        assertEquals(0, node.getLocalTranslation().x, 0.001);
    }

    @Test
    void blendThresholdsAndClamping() {
        Node node = model();
        State blend = new State("Locomotion", "Speed", Arrays.asList("Idle", "Walk", "Run"),
                new float[]{0, 2, 6}, true, 20, 40);
        AnimationGraph graph = new AnimationGraph("Locomotion", Collections.singletonList(new Parameter("Speed", Type.FLOAT, 0)),
                Collections.singletonList(blend), Collections.emptyList());
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, node.getControl(AnimComposer.class));
        float[] speeds = {-10, 0, 1, 2, 4, 6, 20};
        float[] expected = {0, 0, 5, 10, 15, 20, 20};
        for (int i = 0; i < speeds.length; i++) {
            player.setFloat("Speed", speeds[i]);
            node.updateLogicalState(0.1f);
            assertEquals(expected[i], node.getLocalTranslation().x, 0.001);
        }
    }

    @Test
    void triggerConsumedPriorityAndNonInterruptibleTransition() {
        Node node = model();
        AnimationGraph graph = graph(
                new Transition("Idle", "Walk", "Attack", Operator.TRIGGER, 0, 1, -1),
                new Transition("Idle", "Run", "Grounded", Operator.TRUE, 0, 0, -1),
                new Transition("Walk", "Idle", "", Operator.ALWAYS, 0, 0, -1));
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, node.getControl(AnimComposer.class));
        player.trigger("Attack");
        node.updateLogicalState(0.1f);
        assertEquals("Walk", player.getStateName());
        node.updateLogicalState(0.5f);
        assertEquals("Walk", player.getStateName());
        node.updateLogicalState(0.5f);
        node.updateLogicalState(0.1f);
        assertEquals("Idle", player.getStateName());
        node.updateLogicalState(0.1f);
        assertEquals("Run", player.getStateName());
    }

    @Test
    void pauseResetHoldAndRestorePreviousAction() {
        Node node = model();
        AnimComposer composer = node.getControl(AnimComposer.class);
        composer.setCurrentAction("Walk");
        composer.setTime(0.4);
        AnimationGraph graph = new AnimationGraph("Move", Collections.emptyList(),
                Collections.singletonList(state("Move", false)), Collections.emptyList());
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, composer);
        node.updateLogicalState(0.5f);
        assertEquals(5, node.getLocalTranslation().x, 0.001);
        player.setPaused(true);
        node.updateLogicalState(3);
        assertEquals(5, node.getLocalTranslation().x, 0.001);
        player.setPaused(false);
        node.updateLogicalState(0.25f);
        assertEquals(7.5, node.getLocalTranslation().x, 0.001);
        node.updateLogicalState(10);
        assertEquals(10, node.getLocalTranslation().x, 0.001);
        player.restart();
        assertEquals(0, node.getLocalTranslation().x, 0.001);
        player.close();
        player.close();
        assertEquals("Walk", composer.getLayer(AnimComposer.DEFAULT_LAYER).getCurrentActionName());
        assertEquals(0.4, composer.getTime(), 0.001);
    }

    @Test
    void j3oRoundTripAndCloneIsolation() throws Exception {
        Node original = model();
        AnimationGraph definition = graph(new Transition("Idle", "Walk", "Grounded", Operator.FALSE, 0, 0, -1));
        original.setUserData(AnimationGraph.USER_DATA, definition.encode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(original, bytes);
        Node loaded = (Node) new BinaryImporter().load(bytes.toByteArray());
        assertEquals(definition.encode(), AnimationGraph.decode(loaded.getUserData(AnimationGraph.USER_DATA)).encode());
        Node clone = loaded.clone(true);
        AnimationGraphPlayer a = AnimationGraphPlayer.attach(loaded);
        AnimationGraphPlayer b = AnimationGraphPlayer.attach(clone);
        a.setBoolean("Grounded", false);
        loaded.updateLogicalState(0.1f);
        clone.updateLogicalState(0.1f);
        assertEquals("Walk", a.getStateName());
        assertEquals("Idle", b.getStateName());
        assertEquals(10, loaded.getLocalTranslation().x, 0.001);
        assertEquals(0, clone.getLocalTranslation().x, 0.001);
    }

    @Test
    void rejectInvalidDefinitionsAndAssignments() {
        assertThrows(IllegalArgumentException.class, () -> AnimationGraph.decode("version=99"));
        assertThrows(IllegalArgumentException.class, () -> new AnimationGraph("Blend",
                Collections.singletonList(new Parameter("Speed", Type.FLOAT, 0)),
                Collections.singletonList(new State("Blend", "Speed", Arrays.asList("Idle", "Run"),
                        new float[]{1, 1}, true, 0, 0)), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> graph(new Transition("Idle", "Run", "Grounded", Operator.GREATER, 0, 1, -1)));
        Node node = model();
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph(), node.getControl(AnimComposer.class));
        assertThrows(IllegalArgumentException.class, () -> player.setFloat("Grounded", 1));
        assertThrows(IllegalArgumentException.class, () -> player.setFloat("Speed", Float.NaN));
        assertThrows(IllegalStateException.class, () -> new AnimationGraphPlayer(graph(), node.getControl(AnimComposer.class)));
    }

    @Test
    void blendUsesNormalizedPhaseAcrossDifferentClipLengths() {
        Node node = model();
        AnimComposer composer = node.getControl(AnimComposer.class);
        clip(composer, node, "LongMove", 0, 20, 2);
        AnimationGraph graph = new AnimationGraph("Blend", Collections.singletonList(new Parameter("Speed", Type.FLOAT, 0.5f)),
                Collections.singletonList(new State("Blend", "Speed", Arrays.asList("Move", "LongMove"),
                        new float[]{0, 1}, true, 0, 0)), Collections.emptyList());
        new AnimationGraphPlayer(graph, composer);
        node.updateLogicalState(1);
        assertEquals(7.5, node.getLocalTranslation().x, 0.001);
        node.updateLogicalState(1.5f);
        assertEquals(3.75, node.getLocalTranslation().x, 0.001);
    }

    @Test
    void missingTracksUseBaselineInsteadOfPreviousFrame() {
        Node node = model();
        Node child = new Node("part");
        child.setLocalTranslation(3, 0, 0);
        node.attachChild(child);
        AnimComposer composer = node.getControl(AnimComposer.class);
        clip(composer, child, "Part", 13, 13, 1);
        AnimationGraph graph = new AnimationGraph("Blend", Collections.singletonList(new Parameter("Speed", Type.FLOAT, 0.5f)),
                Collections.singletonList(new State("Blend", "Speed", Arrays.asList("Idle", "Part"),
                        new float[]{0, 1}, true, 0, 0)), Collections.emptyList());
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, composer);
        for (int i = 0; i < 100; i++) {
            node.updateLogicalState(0.01f);
            assertEquals(8, child.getLocalTranslation().x, 0.001);
        }
        player.close();
        assertEquals(3, child.getLocalTranslation().x, 0.001);
    }

    @Test
    void morphTracksBlendWithoutMutatingOriginalWeights() {
        Node node = new Node("Morph character");
        com.jme3.scene.Geometry geometry = new com.jme3.scene.Geometry("Morph", new com.jme3.scene.Mesh());
        geometry.getMesh().addMorphTarget(new com.jme3.scene.mesh.MorphTarget("Smile"));
        geometry.setMorphState(new float[]{0});
        node.attachChild(geometry);
        AnimComposer composer = new AnimComposer();
        node.addControl(composer);
        for (int i = 0; i < 2; i++) {
            AnimClip clip = new AnimClip("Morph" + i);
            clip.setTracks(new AnimTrack[]{new MorphTrack(geometry, new float[]{0, 1}, new float[]{i, i}, 1)});
            composer.addAnimClip(clip);
        }
        AnimationGraph graph = new AnimationGraph("Blend", Collections.singletonList(new Parameter("Weight", Type.FLOAT, 0.25f)),
                Collections.singletonList(new State("Blend", "Weight", Arrays.asList("Morph0", "Morph1"),
                        new float[]{0, 1}, true, 0, 0)), Collections.emptyList());
        AnimationGraphPlayer player = new AnimationGraphPlayer(graph, composer);
        node.updateLogicalState(0.25f);
        assertEquals(0.25, geometry.getMorphState()[0], 0.001);
        player.close();
        assertEquals(0, geometry.getMorphState()[0], 0.001);
    }
}

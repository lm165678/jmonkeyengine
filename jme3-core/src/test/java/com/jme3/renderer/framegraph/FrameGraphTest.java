package com.jme3.renderer.framegraph;

import com.jme3.renderer.*;
import com.jme3.system.NullRenderer;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameGraphTest {
    @Test
    public void resourceExpansionRetainsProducersOfExternalOutputs() {
        Definition definition = new Definition();
        StringBuilder external = new StringBuilder();
        try (FrameGraph graph = new FrameGraph()) {
            graph.addResourceExpansionPass("Effect", planned -> {
                ResourceHandle<StringBuilder> source = planned.createResource("Source", definition);
                ResourceHandle<StringBuilder> output = planned.importResource("Output", StringBuilder.class,
                        () -> external);
                planned.addPass("Produce", pass -> pass.get(source).append("result")).write(source);
                planned.addPass("Present", pass -> pass.get(output).append(pass.get(source)))
                        .read(source).write(output);
            });
            graph.execute(manager, viewport);
            assertEquals("result", external.toString());
            assertEquals(1, definition.created);
            assertTrue(graph.getCulledPasses().isEmpty());
        }
    }

    @Test
    public void resourceExpansionCullsDeadWorkWithoutReorderingFollowingPasses() {
        Definition definition = new Definition();
        List<String> calls = new ArrayList<>();
        try (FrameGraph graph = new FrameGraph()) {
            graph.addPass("Before", pass -> calls.add("before")).sideEffect();
            graph.addResourceExpansionPass("Group", planned -> {
                ResourceHandle<StringBuilder> unused = planned.createResource("Unused", definition);
                planned.addPass("Dead", pass -> fail("Dead expanded pass executed")).write(unused);
                planned.addPass("Required", pass -> calls.add("required")).sideEffect();
                planned.addResourceExpansionPass("Nested", nested -> {
                    nested.addPass("Check", pass -> calls.add("nested")).sideEffect();
                });
            });
            graph.addPass("After", pass -> calls.add("after")).sideEffect();
            graph.execute(manager, viewport);
            assertEquals(Arrays.asList("before", "required", "nested", "after"), calls);
            assertTrue(graph.getCulledPasses().contains("Group/Dead"));
            assertFalse(graph.getExecutedPasses().contains("Group/Dead"));
            assertEquals(0, definition.created);
        }
    }

    @Test
    public void historyInvalidationRetainsAllocationAndRemovalReleasesName() {
        Definition definition = new Definition();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> history = graph.findOrCreatePersistentResource("History", definition);
            graph.addPass("Write", pass -> pass.get(history).append("old")).write(history);
            graph.exportResource(history);
            graph.execute(manager, viewport);
            assertTrue(graph.isPersistentResourceValid("History"));
            assertTrue(graph.invalidatePersistentResource("History"));
            assertFalse(graph.isPersistentResourceValid("History"));
            fails(() -> graph.getExported(history));
            assertEquals(0, definition.destroyed);
            graph.execute(manager, viewport);
            assertEquals(1, definition.created);
            fails(() -> graph.removePersistentResource("History"));
            graph.beginFrame();
            assertTrue(graph.removePersistentResource("History"));
            assertEquals(1, definition.destroyed);
            Definition replacement = new Definition();
            ResourceHandle<StringBuilder> next = graph.findOrCreatePersistentResource("History", replacement);
            graph.addPass("New", pass -> pass.get(next).append("new")).write(next);
            graph.execute(manager, viewport);
            assertTrue(graph.isPersistentResourceValid("History"));
            assertEquals(1, replacement.created);
            assertFalse(graph.invalidatePersistentResource("Missing"));
        }
    }

    @Test
    public void trimmingHonorsAgeExportsAndPersistentOwnership() {
        Definition definition = new Definition();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> temporary = graph.createResource("Temporary", definition);
            ResourceHandle<StringBuilder> history = graph.findOrCreatePersistentResource("History", definition);
            graph.addPass("Temp", pass -> pass.get(temporary).append("x")).write(temporary);
            graph.addPass("History", pass -> pass.get(history).append("h")).write(history);
            graph.exportResource(temporary);
            graph.execute(manager, viewport);
            assertEquals(0, graph.trimUnusedResources(0));
            assertEquals(1, graph.getPoolStats().retainedTransientAllocations);
            graph.beginFrame();
            assertEquals(0, graph.trimUnusedResources(1));
            graph.execute(manager, viewport);
            assertEquals(1, graph.trimUnusedResources(1));
            assertEquals(1, definition.destroyed);
            assertEquals(0, graph.getPoolStats().transientAllocations);
            assertEquals(1, graph.getPoolStats().persistentAllocations);
            assertTrue(graph.isPersistentResourceValid("History"));
            fails(() -> graph.trimUnusedResources(-1));
        }
        assertEquals(2, definition.destroyed);
    }

    @Test
    public void diagnosticsExposeLifetimesCullingAndReuseWithoutAllocation() {
        Definition definition = new Definition();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> live = graph.createResource("Live", definition);
            ResourceHandle<StringBuilder> dead = graph.createResource("Dead", definition);
            graph.addPass("Draw", pass -> pass.get(live).append("draw")).write(live).sideEffect();
            graph.addPass("Unused", pass -> fail("Culled pass executed")).write(dead);
            String report = graph.describe();
            assertTrue(report.contains("CULLED Unused"));
            assertTrue(report.contains("write=[Live]"));
            assertTrue(report.contains("Dead kind=transient lifetime=[-1,-1]"));
            assertEquals(0, definition.created);
            graph.execute(manager, viewport);
            graph.execute(manager, viewport);
            assertEquals(1, graph.getPoolStats().totalTransientAllocations);
            assertEquals(1, graph.getPoolStats().transientReuses);
            assertEquals(1, graph.trimUnusedResources(0));
            graph.execute(manager, viewport);
            assertEquals(2, graph.getPoolStats().totalTransientAllocations);
        }
    }

    @Test
    public void resourceMaintenanceCannotRunInsideDrawingPass() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> history = graph.findOrCreatePersistentResource("H", new Definition());
            graph.addPass("Draw", pass -> {
                fails(() -> graph.trimUnusedResources(0));
                fails(() -> graph.invalidatePersistentResource("H"));
                fails(() -> graph.removePersistentResource("H"));
            }).write(history);
            graph.execute(manager, viewport);
        }
    }

    @Test
    public void plannedTargetsUseTheCameraSizeAfterPreFrame() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        graph.addPass("PreFrame", context -> viewport.getCamera().resize(40, 30, false)).sideEffect();
        graph.addExpansionPass("Draw", current -> {
            ResourceHandle<StringBuilder> target = current.createResource("Target", definition);
            current.addPass("Write", context -> context.get(target).append("draw")).write(target);
        });
        graph.execute(manager, viewport);
        assertEquals(Collections.singletonList("40x30"), definition.sizes);
        graph.close();
    }

    @Test
    public void planningCannotRetroactivelyExtendAnExpiredTransient() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        ResourceHandle<StringBuilder> transientValue = graph.createResource("Transient", definition);
        graph.addPass("Write", context -> context.get(transientValue).append("old"))
            .write(transientValue).sideEffect();
        graph.addExpansionPass("Late", current -> {
            current.addPass("Read", context -> context.get(transientValue)).read(transientValue);
        });
        fails(() -> graph.execute(manager, viewport));
        graph.close();
        assertEquals(1, definition.destroyed);
    }

    @Test
    public void expansionsShareOneScheduleAndPoolAndExposeExecutedNames() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        List<String> calls = new ArrayList<>();
        graph.addExpansionPass("First", current -> {
            ResourceHandle<StringBuilder> value = current.createResource("Target", definition);
            current.addPass("Write", context -> {
                context.get(value).setLength(0);
                context.get(value).append("first");
                calls.add("first");
            }).write(value);
            current.addPass("Read", context -> {
                assertEquals("first", context.get(value).toString());
            }).read(value).sideEffect();
        });
        graph.addExpansionPass("Second", current -> {
            current.addExpansionPass("Nested", nested -> {
                ResourceHandle<StringBuilder> value = nested.createResource("Target", definition);
                nested.addPass("Write", context -> {
                    context.get(value).setLength(0);
                    context.get(value).append("second");
                    calls.add("second");
                }).write(value);
            });
        });
        graph.addPass("End", context -> calls.add("end")).sideEffect();
        graph.execute(manager, viewport);
        assertEquals(Arrays.asList("first", "second", "end"), calls);
        assertEquals(Arrays.asList("First", "First/Write", "First/Read", "Second",
            "Second/Nested", "Second/Nested/Write", "End"), graph.getExecutedPasses());
        assertEquals(graph.getExecutionOrder(), graph.getExecutedPasses());
        assertEquals(1, definition.created);
        fails(() -> graph.execute(manager, viewport));
        graph.beginFrame();
        graph.addPass("Next", context -> {}).sideEffect();
        graph.execute(manager, viewport);
        assertEquals(Collections.singletonList("Next"), graph.getExecutedPasses());
        graph.close();
        assertEquals(1, definition.destroyed);
    }

    @Test
    public void captureGroupsAreBalancedWhenAPassFails() {
        List<String> markers = new ArrayList<>();
        RenderManager debugManager = new RenderManager(new NullRenderer() {
            @Override
            public void pushDebugGroup(String name) {
                markers.add("begin " + name);
            }

            @Override
            public void popDebugGroup() {
                markers.add("end");
            }
        });
        FrameGraph graph = new FrameGraph();
        graph.addExpansionPass("Group", current -> {
            current.addPass("Failure", context -> {
                throw new IllegalStateException("expected");
            }).sideEffect();
        });
        graph.addPass("Unreached", context -> fail()).sideEffect();
        fails(() -> graph.execute(debugManager, viewport));
        assertEquals(Arrays.asList("begin graph/Group", "end", "begin graph/Group/Failure", "end"), markers);
        assertEquals(Arrays.asList("Group", "Group/Failure"), graph.getExecutedPasses());
        graph.close();
    }

    @Test
    public void dynamicFramesReuseAllocationsButRejectExpiredHandlesAndPasses() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        graph.beginFrame(80, 60);
        ResourceHandle<StringBuilder> old = graph.findOrCreate("First", definition);
        assertSame(old, graph.findOrCreate("First", definition));
        fails(() -> graph.findOrCreate("First", new Definition()));
        fails(() -> graph.findResource("First", Integer.class));
        FrameGraph.PassBuilder oldPass = graph.addPass("Old", context -> {
            context.get(old).append("old");
        }).write(old).sideEffect();
        graph.execute(manager, viewport);
        graph.beginFrame(80, 60);
        ResourceHandle<StringBuilder> current = graph.findOrCreate("DifferentName", definition);
        fails(() -> graph.addPass("Stale", context -> {}).read(old));
        fails(() -> oldPass.write(current));
        graph.addPass("Current", context -> {
            context.get(current).setLength(0);
        }).write(current).sideEffect();
        graph.execute(manager, viewport);
        assertEquals(1, definition.created);
        graph.close();
        assertEquals(1, definition.destroyed);
    }

    @Test
    public void persistentHistorySurvivesRebuildAndNeverAliasesTransientPool() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        graph.beginFrame(80, 60);
        ResourceHandle<StringBuilder> history =
            graph.findOrCreatePersistentResource("History", definition);
        ResourceHandle<StringBuilder> transientValue = graph.findOrCreate("Scratch", definition);
        assertFalse(graph.isPersistentResourceValid("History"));
        Object[] allocated = new Object[2];
        graph.addPass("Store", context -> {
            allocated[0] = context.get(history);
            context.get(history).append("retained");
        }).write(history);
        graph.addPass("Transient", context -> {
            allocated[1] = context.get(transientValue);
        }).write(transientValue).sideEffect();
        graph.execute(manager, viewport);
        assertNotSame(allocated[0], allocated[1]);
        assertEquals(2, definition.created);
        graph.beginFrame(80, 60);
        assertTrue(graph.isPersistentResourceValid("History"));
        fails(() -> graph.findOrCreate("History", definition));
        ResourceHandle<StringBuilder> next =
            graph.findOrCreatePersistentResource("History", definition);
        graph.addPass("Read", context -> {
            assertSame(allocated[0], context.get(next));
            assertEquals("retained", context.get(next).toString());
        }).read(next).sideEffect();
        graph.execute(manager, viewport);
        graph.beginFrame(160, 90);
        assertFalse(graph.isPersistentResourceValid("History"));
        assertEquals(2, definition.destroyed);
        graph.close();
        assertEquals(2, definition.destroyed);
    }

    @Test
    public void uninitializedPersistentReadFailsAndAbortInvalidatesHistory() {
        FrameGraph graph = new FrameGraph();
        Definition definition = new Definition();
        ResourceHandle<StringBuilder> history =
            graph.findOrCreatePersistentResource("History", definition);
        graph.addPass("Read", context -> {}).read(history).sideEffect();
        fails(() -> graph.execute(manager, viewport));
        graph.beginFrame(80, 60);
        ResourceHandle<StringBuilder> next =
            graph.findOrCreatePersistentResource("History", definition);
        graph.addPass("Store", context -> {
            context.get(next).append("partial");
            context.abort();
        }).write(next);
        assertFalse(graph.execute(manager, viewport));
        assertFalse(graph.isPersistentResourceValid("History"));
        graph.close();
    }

    private final RenderManager manager = new RenderManager(new NullRenderer());
    private final ViewPort viewport = new ViewPort("graph", new Camera(80, 60));
    private static final class Definition implements ResourceDefinition<StringBuilder> {
        int created, destroyed;
        final List<String> sizes = new ArrayList<>();
        @Override
        public Class<StringBuilder> getType() {
            return StringBuilder.class;
        }
        @Override
        public StringBuilder create(Renderer r, int w, int h) {
            created++;
            sizes.add(w + "x" + h);
            return new StringBuilder();
        }
        @Override
        public void destroy(StringBuilder value) {
            destroyed++;
        }
    }
    private static void fails(Runnable action) {
        try {
            action.run();
            fail("Expected failure");
        } catch (IllegalStateException | IllegalArgumentException expected) {
        }
    }
    @Test
    public void sortsDependenciesAndCullsUnusedAllocations() {
        FrameGraph g = new FrameGraph();
        Definition d = new Definition(), unused = new Definition();
        ResourceHandle<StringBuilder> a = g.createResource("a", d), b = g.createResource("b", d),
                                      dead = g.createResource("dead", unused);
        g.addPass("consumer", c -> c.get(b).append(c.get(a))).read(a).write(b);
        g.addPass("dead", c -> c.get(dead).append("never")).write(dead);
        g.addPass("producer", c -> c.get(a).append("value")).write(a);
        g.exportResource(b);
        assertEquals(Arrays.asList("producer", "consumer"), g.getExecutionOrder());
        assertEquals(Collections.singletonList("dead"), g.getCulledPasses());
        assertTrue(g.execute(manager, viewport));
        assertEquals("value", g.getExported(b).toString());
        assertEquals(2, d.created);
        assertEquals(0, unused.created);
        g.close();
        assertEquals(2, d.destroyed);
    }
    @Test
    public void aliasesOnlyDisjointLifetimesAndReusesAcrossFrames() {
        FrameGraph g = new FrameGraph();
        Definition d = new Definition();
        ResourceHandle<StringBuilder> a = g.createResource("a", d), b = g.createResource("b", d);
        Object[] identities = new Object[2];
        g.addPass("a", c -> {
             identities[0] = c.get(a);
             c.get(a).setLength(0);
             c.get(a).append("a");
         }).write(a);
        FrameGraph.PassBuilder consume =
            g.addPass("consume", c -> assertEquals("a", c.get(a).toString())).read(a).sideEffect();
        g.addPass("b", c -> {
             identities[1] = c.get(b);
             c.get(b).setLength(0);
             c.get(b).append("b");
         }).write(b).after(consume);
        g.exportResource(b);
        g.execute(manager, viewport);
        assertSame(identities[0], identities[1]);
        assertEquals(1, d.created);
        g.execute(manager, viewport);
        assertEquals(1, d.created);
        assertEquals("b", g.getExported(b).toString());
        viewport.getCamera().resize(160, 90, false);
        g.execute(manager, viewport);
        assertEquals(Arrays.asList("80x60", "160x90"), d.sizes);
        assertEquals(1, d.destroyed);
        g.clearResources();
        assertEquals(2, d.destroyed);
        fails(() -> g.getExported(b));
        g.close();
        g.close();
        assertEquals(2, d.destroyed);
    }
    @Test
    public void importsAreBorrowedDynamicAndNullIsValid() {
        FrameGraph g = new FrameGraph();
        StringBuilder[] external = {new StringBuilder()};
        int[] calls = {0};
        ResourceHandle<StringBuilder> out = g.importResource("out", StringBuilder.class, () -> {
            calls[0]++;
            return external[0];
        });
        g.addPass("external", c -> {
             if (c.get(out) != null) {
                 c.get(out).append("x");
             }
         }).read(out).write(out);
        g.exportResource(out);
        g.execute(manager, viewport);
        assertSame(external[0], g.getExported(out));
        assertEquals(1, calls[0]);
        external[0] = null;
        g.execute(manager, viewport);
        assertNull(g.getExported(out));
        assertEquals(2, calls[0]);
        g.close();
    }
    @Test
    public void rejectsMissingWritersMultipleWritersCyclesAndFeedback() {
        FrameGraph missing = new FrameGraph();
        ResourceHandle<StringBuilder> h = missing.createResource("h", new Definition());
        missing.addPass("reader", c -> {}).read(h).sideEffect();
        fails(missing::compile);
        FrameGraph multi = new FrameGraph();
        ResourceHandle<StringBuilder> m = multi.createResource("m", new Definition());
        multi.addPass("a", c -> {}).write(m);
        multi.addPass("b", c -> {}).write(m);
        fails(multi::compile);
        FrameGraph cycle = new FrameGraph();
        FrameGraph.PassBuilder a = cycle.addPass("a", c -> {}), b = cycle.addPass("b", c -> {});
        a.after(b);
        b.after(a);
        fails(cycle::compile);
        FrameGraph feedback = new FrameGraph();
        ResourceHandle<StringBuilder> f = feedback.createResource("f", new Definition());
        feedback.addPass("feedback", c -> {}).read(f).write(f);
        fails(feedback::compile);
    }
    @Test
    public void rejectsForeignHandlesUndeclaredAccessAndMutationAfterCompile() {
        FrameGraph g = new FrameGraph(), other = new FrameGraph();
        ResourceHandle<StringBuilder> h = g.createResource("h", new Definition());
        fails(() -> other.addPass("bad", c -> {}).read(h));
        g.addPass("bad", c -> c.get(h)).sideEffect();
        g.compile();
        fails(() -> g.addPass("late", c -> {}));
        fails(() -> g.execute(manager, viewport));
        g.close();
    }
    @Test
    public void failureAndAbortReleaseLifetimesWithoutPublishingOutputs() {
        FrameGraph g = new FrameGraph();
        Definition d = new Definition();
        ResourceHandle<StringBuilder> h = g.createResource("h", d);
        int[] mode = {0};
        FrameGraphContext[] borrowed = {null};
        int[] downstream = {0};
        g.addPass("first", c -> {
             borrowed[0] = c;
             c.get(h).setLength(0);
             if (mode[0] == 0) {
                 c.abort();
             }
             if (mode[0] == 1) {
                 throw new RuntimeException("boom");
             }
             c.get(h).append("ok");
         }).write(h);
        g.addPass("second", c -> downstream[0]++).read(h).sideEffect();
        g.exportResource(h);
        assertFalse(g.execute(manager, viewport));
        assertEquals(0, downstream[0]);
        fails(() -> g.getExported(h));
        fails(() -> borrowed[0].get(h));
        mode[0] = 1;
        fails(() -> g.execute(manager, viewport));
        assertEquals(1, d.created);
        mode[0] = 2;
        assertTrue(g.execute(manager, viewport));
        assertEquals("ok", g.getExported(h).toString());
        assertEquals(1, downstream[0]);
        g.close();
        assertEquals(1, d.destroyed);
    }
    @Test
    public void rejectsReentrancyAndCrossViewportReuse() {
        FrameGraph g = new FrameGraph();
        g.addPass("reentrant", c -> fails(() -> g.execute(manager, viewport))).sideEffect();
        g.execute(manager, viewport);
        fails(() -> g.execute(manager, new ViewPort("other", new Camera(80, 60))));
        g.close();
        fails(() -> g.execute(manager, viewport));
    }
    @Test
    public void renderTargetDefinitionPreservesMrtDepthAndRelativeSize() {
        RenderTargetDefinition a = new RenderTargetDefinition(
            .5f, 1, com.jme3.texture.Image.Format.Depth24, com.jme3.texture.Image.Format.RGBA16F);
        assertEquals(a,
            new RenderTargetDefinition(.5f, 1, com.jme3.texture.Image.Format.Depth24,
                com.jme3.texture.Image.Format.RGBA16F));
        RenderTarget t = a.create(manager.getRenderer(), 81, 61);
        assertEquals(41, t.getFrameBuffer().getWidth());
        assertEquals(31, t.getFrameBuffer().getHeight());
        assertNotNull(t.getDepthTexture());
        assertEquals(1, t.getColorCount());
        a.destroy(t);
    }
}

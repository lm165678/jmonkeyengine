/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.system;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for SDK modules that depend on the engine, but are invisible to its loader. */
class CustomContextClassLoaderTest {
    @TempDir
    Path directory;

    private JmeDesktopSystem factory() {
        return new JmeDesktopSystem() {
            @Override
            public void initialize(AppSettings settings) {
                // Context construction only: no native libraries or graphics device.
            }
        };
    }

    private URLClassLoader pluginLoader() throws Exception {
        Path source = directory.resolve("PluginContext.java");
        Files.writeString(source,
                "package isolated.sdk; public final class PluginContext extends com.jme3.system.NullContext {}",
                StandardCharsets.UTF_8);
        String coreClasses = Path.of(NullContext.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", coreClasses, "-d", directory.toString(), source.toString());
        assertEquals(0, result);
        return new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},
                JmeContext.class.getClassLoader());
    }

    @Test
    void registeredPluginClassSurvivesSettingsCopyAndEngineLoaderIsolation() throws Exception {
        try (URLClassLoader loader = pluginLoader()) {
            Class<? extends JmeContext> plugin = loader.loadClass("isolated.sdk.PluginContext")
                    .asSubclass(JmeContext.class);
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(plugin.getName(), false, JmeDesktopSystem.class.getClassLoader()));
            AppSettings original = new AppSettings(false);
            original.setCustomRenderer(plugin);
            AppSettings copied = new AppSettings(true);
            copied.copyFrom(original);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                // Even an engine-only TCCL must not hide the explicitly registered plugin class.
                Thread.currentThread().setContextClassLoader(JmeDesktopSystem.class.getClassLoader());
                JmeContext context = factory().newContext(copied, JmeContext.Type.Display);
                assertSame(plugin, context.getClass());
                assertSame(plugin, context.getSettings().getCustomRendererClass());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void persistedClassNameCanUseHostLoaderWithoutPersistingTheClassObject() throws Exception {
        try (URLClassLoader loader = pluginLoader()) {
            Class<? extends JmeContext> plugin = loader.loadClass("isolated.sdk.PluginContext")
                    .asSubclass(JmeContext.class);
            AppSettings settings = new AppSettings(false);
            settings.setCustomRenderer(plugin);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            settings.save(output);
            AppSettings loaded = new AppSettings(false);
            loaded.load(new ByteArrayInputStream(output.toByteArray()));
            assertNull(loaded.getCustomRendererClass());
            assertEquals(settings.getRenderer(), loaded.getRenderer());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(loader);
                assertSame(plugin, factory().newContext(loaded, JmeContext.Type.Display).getClass());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void mergingAndRestoringBuiltInRendererDoNotRetainAnActiveCustomRegistration() {
        AppSettings registered = new AppSettings(false);
        registered.setCustomRenderer(NullContext.class);
        AppSettings merged = new AppSettings(false);
        merged.mergeFrom(registered);
        assertSame(NullContext.class, merged.getCustomRendererClass());
        merged.setRenderer(AppSettings.LWJGL_OPENGL33);
        merged.mergeFrom(registered);
        assertNull(merged.getCustomRendererClass());
        assertEquals(AppSettings.LWJGL_OPENGL33, merged.getRenderer());
        registered.copyFrom(merged);
        assertNull(registered.getCustomRendererClass());
    }

    @Test
    void classNameOnlySettingsStillFallBackToEngineLoader() {
        AppSettings settings = new AppSettings(false);
        settings.putString("Renderer", "CUSTOM" + NullContext.class.getName());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            assertInstanceOf(NullContext.class, factory().newContext(settings, JmeContext.Type.Display));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}

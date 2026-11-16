/*
 * Copyright (c) 2009-2021 jMonkeyEngine
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
package com.jme3.scene.plugins;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetLoader;
import com.jme3.asset.AssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.TestUtil;
import com.jme3.texture.Image;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;

public class OBJLoaderTest {
    private AssetManager assetManager;
    
    @BeforeEach
    public void init() {
        assetManager = TestUtil.createAssetManager();
        // texture loaders are outside of core, so creating stub
        assetManager.registerLoader(PngLoaderStub.class, "png");
        
    }
    
    @Test
    public void testHappyPath() {
        Node scene = (Node) assetManager.loadModel(new ModelKey("OBJLoaderTest/TwoChairs.obj"));
        String sceneAsString = toDiffFriendlyString("", scene);
        System.out.println(sceneAsString);
        String expectedText = "" +
            // generated root name (as before named groups support)
            "TwoChairs-objnode\n" +
            // unnamed geometry with generated name (as before named groups support).
            // Smoothing preserves the existing material/group hierarchy.
            "  TwoChairs-geom-0 (material: dot_purple)\n" +
            // named group as Geometry
            "  Chair 2 (material: dot_purple)\n" +
            // named group as Geometry
            "  Pillow 2 (material: dot_red)\n" +
            // named group as node with two different Geometry instances,
            // because two materials are used (as before named groups support)
            "  Podium\n" +
            "    TwoChairs-geom-3 (material: dot_red)\n" +
            "    TwoChairs-geom-4 (material: dot_blue)\n" +
            // named group as Geometry
            "  Pillow 1 (material: dot_green)";
        assertEquals(expectedText, sceneAsString.trim());
    }
    
    private Mesh loadText(String text) throws IOException {
        AssetInfo info = new AssetInfo(assetManager, new ModelKey("generated.obj")) {
            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            }
        };
        return ((Geometry) new OBJLoader().load(info)).getMesh();
    }

    @Test
    public void generatesNormalsAndTangentsWithoutWarnings() throws IOException {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(OBJLoader.class.getName());
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }
            @Override
            public void flush() {
            }
            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            Mesh mesh = loadText("o Example\nv 0 0 0\nv 1 0 0\nv 0 1 0\n"
                    + "vt 0 0\nvt 1 0\nvt 0 1\ns 1\nf 1/1 2/2 3/3\n");
            assertNotNull(mesh.getBuffer(Type.Normal));
            assertNotNull(mesh.getBuffer(Type.Tangent));
            FloatBuffer normals = mesh.getFloatBuffer(Type.Normal);
            for (int i = 0; i < mesh.getVertexCount(); i++) {
                assertEquals(1f, normals.get(i * 3 + 2), 0.00001f);
            }
            FloatBuffer tangents = mesh.getFloatBuffer(Type.Tangent);
            for (int i = 0; i < tangents.limit(); i++) {
                assertTrue(Float.isFinite(tangents.get(i)));
            }
            assertTrue(warnings.isEmpty(), warnings.toString());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void smoothingGroupsShareNormalsButOffSplitsEdges() throws IOException {
        String positions = "v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\n";
        String faces = "f 1 2 3\nf 1 4 2\n";
        Mesh smooth = loadText(positions + "s 1\n" + faces);
        Mesh flat = loadText(positions + "s off\n" + faces);
        assertTrue(smooth.getVertexCount() >= 4);
        assertEquals(6, flat.getVertexCount());
        assertNotNull(smooth.getBuffer(Type.Tangent));
        assertEquals((float) Math.sqrt(0.5), smooth.getFloatBuffer(Type.Normal).get(1), 0.00001f);
        assertEquals((float) Math.sqrt(0.5), smooth.getFloatBuffer(Type.Normal).get(2), 0.00001f);
    }

    @Test
    public void preservesExplicitNormalsAndHandlesPartialUvs() throws IOException {
        Mesh mesh = loadText("v 0 0 0\nv 1 0 0\nv 0 1 0\nvn 0 1 0\nvt 0 0\n"
                + "f 1/1/1 2 3\n");
        assertEquals(1f, mesh.getFloatBuffer(Type.Normal).get(1));
        assertEquals(1f, mesh.getFloatBuffer(Type.Normal).get(5));
        assertNotNull(mesh.getBuffer(Type.Tangent));
        assertEquals(0f, mesh.getFloatBuffer(Type.TexCoord).get(0));
        assertEquals(1f, mesh.getFloatBuffer(Type.TexCoord).get(2));
    }

    private static String toDiffFriendlyString(String indent, Spatial spatial) {
        if (spatial instanceof Geometry) {
            return indent + spatial.getName() + " (material: "+((Geometry) spatial).getMaterial().getName()+")\n";
        }
        if (spatial instanceof Node) {
            StringBuilder s = new StringBuilder();
            s.append(indent).append(spatial.getName()).append("\n");
            Node node = (Node) spatial;
            for (final Spatial child : node.getChildren()) {
                s.append(toDiffFriendlyString(indent + "  ", child));
            }
            return s.toString();
        }
        return indent + spatial + "\n";
    }
    
    public static class PngLoaderStub implements AssetLoader {
        @Override
        public Object load(final AssetInfo assetInfo) {
            return new Image();
        }
    }
}
/*
 * Copyright (c) 2023 jMonkeyEngine
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

package com.jme3.scene.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.bounding.BoundingBox;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Matrix4f;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import org.junit.jupiter.api.Test;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Tests selected methods of the Mesh class.
 *
 * @author Melvyn Linke
 */
public class MeshTest {

    /**
     * Tests getVertexCount() on a empty Mesh.
     */
    @Test
    public void testVertexCountOfEmptyMesh() {
        final Mesh mesh = new Mesh();

        assertEquals(-1, mesh.getVertexCount());
    }

    @Test
    public void testHardwareSkinningPreparesInMemoryMeshWithoutPlaceholders() {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{1, 2, 3});
        mesh.setBuffer(VertexBuffer.Type.BoneIndex, 4, new short[]{7, 2, 0, 0});
        mesh.setBuffer(VertexBuffer.Type.BoneWeight, 4, new float[]{0.75f, 0.25f, 0, 0});
        mesh.setMaxNumWeights(2);
        mesh.generateBindPose();

        mesh.prepareForAnim(false);
        VertexBuffer indices = mesh.getBuffer(VertexBuffer.Type.HWBoneIndex);
        VertexBuffer weights = mesh.getBuffer(VertexBuffer.Type.HWBoneWeight);
        assertEquals(VertexBuffer.Format.UnsignedShort, indices.getFormat());
        assertTrue(indices.getData().isDirect());
        assertTrue(weights.getData().isDirect());
        assertEquals(7, ((ShortBuffer) indices.getData()).get(0));
        assertEquals(0.25f, ((FloatBuffer) weights.getData()).get(1));

        mesh.prepareForAnim(true);
        mesh.prepareForAnim(false);
        assertSame(indices, mesh.getBuffer(VertexBuffer.Type.HWBoneIndex));
        assertSame(weights, mesh.getBuffer(VertexBuffer.Type.HWBoneWeight));
    }

    @Test
    public void testPatchModeDoesNotCollide() {
        final Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Patch);
        mesh.setPatchVertexCount(4);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f
        });
        mesh.setBuffer(VertexBuffer.Type.Index, 1, new short[]{0, 1, 2, 3});

        CollisionResults results = new CollisionResults();
        int collisions = mesh.collideWith(new Ray(new Vector3f(0.5f, 0.5f, 1f),
                new Vector3f(0f, 0f, -1f)), Matrix4f.IDENTITY, new BoundingBox(), results);

        assertEquals(0, collisions);
        assertEquals(0, results.size());
    }
}

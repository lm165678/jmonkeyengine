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
package com.jme3.renderer.deferred;

import com.jme3.light.*;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import java.util.Arrays;
import java.util.List;

/**
 * Conservative CPU light binning. Lists contain indices into the caller's light
 * list; the caller must supply a single light scope, not merge scene subtrees.
 * Storage grows instead of silently dropping lights on tile overflow.
 * Instances are reusable, but not thread safe.
 */
public final class TileLightGrid {
    private final int tileSize;
    private int columns, rows;
    private int[][] indices = new int[0][];
    private int[] counts = new int[0];
    private final Vector3f view = new Vector3f();
    private final Vector3f corner = new Vector3f();
    private final Vector3f projected = new Vector3f();

    public TileLightGrid(int tileSize) {
        if (tileSize <= 0) throw new IllegalArgumentException("tileSize must be positive");
        this.tileSize = tileSize;
    }

    /** Rebuilds conservative screen-space light lists for a full-frame camera. */
    public void build(Camera camera, List<? extends Light> lights) {
        if (camera.getWidth() <= 0 || camera.getHeight() <= 0) {
            throw new IllegalArgumentException("Camera dimensions must be positive");
        }
        if (camera.getViewPortLeft() != 0 || camera.getViewPortBottom() != 0
                || camera.getViewPortRight() != 1 || camera.getViewPortTop() != 1) {
            throw new IllegalArgumentException("Tile grid requires a full-frame camera");
        }
        columns = (camera.getWidth() - 1) / tileSize + 1;
        rows = (camera.getHeight() - 1) / tileSize + 1;
        int size = Math.multiplyExact(columns, rows);
        if (counts.length < size) {
            counts = new int[size];
            indices = Arrays.copyOf(indices, size);
        } else {
            Arrays.fill(counts, 0, size, 0);
        }
        for (int i = 0; i < lights.size(); i++) {
            Light light = lights.get(i);
            if (!light.isEnabled()) continue;
            Vector3f position;
            float radius;
            if (light instanceof PointLight) {
                position = ((PointLight) light).getPosition();
                radius = ((PointLight) light).getRadius();
            } else if (light instanceof SpotLight) {
                position = ((SpotLight) light).getPosition();
                radius = ((SpotLight) light).getSpotRange();
            } else if (light instanceof DirectionalLight || light instanceof AmbientLight) {
                add(i, 0, 0, columns - 1, rows - 1);
                continue;
            } else {
                throw new IllegalArgumentException("Unsupported tiled light: " + light.getClass());
            }
            if (radius == 0 || !Float.isFinite(radius)) {
                add(i, 0, 0, columns - 1, rows - 1);
                continue;
            }
            camera.getViewMatrix().mult(position, view);
            float depth = -view.z;
            if (depth + radius < camera.getFrustumNear()
                    || depth - radius > camera.getFrustumFar()) continue;
            // Near-plane intersection must not project behind the eye.
            if (depth - radius <= camera.getFrustumNear()) {
                add(i, 0, 0, columns - 1, rows - 1);
                continue;
            }
            float minX = Float.POSITIVE_INFINITY, minY = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
            // Project a view-space bounding box enclosing the influence sphere.
            for (int c = 0; c < 8; c++) {
                corner.set(view.x + ((c & 1) == 0 ? -radius : radius),
                        view.y + ((c & 2) == 0 ? -radius : radius),
                        view.z + ((c & 4) == 0 ? -radius : radius));
                float w = camera.getProjectionMatrix().multProj(corner, projected);
                float x = (projected.x / w * .5f + .5f) * camera.getWidth();
                float y = (projected.y / w * .5f + .5f) * camera.getHeight();
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            if (maxX < 0 || maxY < 0 || minX >= camera.getWidth() || minY >= camera.getHeight()) continue;
            add(i, Math.max(0, (int) Math.floor(minX / tileSize)),
                    Math.max(0, (int) Math.floor(minY / tileSize)),
                    Math.min(columns - 1, (int) Math.floor(maxX / tileSize)),
                    Math.min(rows - 1, (int) Math.floor(maxY / tileSize)));
        }
    }

    private void add(int light, int x0, int y0, int x1, int y1) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int tile = y * columns + x;
                int count = counts[tile];
                if (indices[tile] == null) indices[tile] = new int[8];
                else if (count == indices[tile].length) indices[tile] = Arrays.copyOf(indices[tile], count * 2);
                indices[tile][count] = light;
                counts[tile] = count + 1;
            }
        }
    }

    public int getTileSize() { return tileSize; }
    public int getColumns() { return columns; }
    public int getRows() { return rows; }
    public int getLightCount(int x, int y) { return counts[tile(x, y)]; }
    public int getLightIndex(int x, int y, int index) {
        int tile = tile(x, y);
        if (index < 0 || index >= counts[tile]) throw new IndexOutOfBoundsException();
        return indices[tile][index];
    }
    private int tile(int x, int y) {
        if (x < 0 || y < 0 || x >= columns || y >= rows) throw new IndexOutOfBoundsException();
        return y * columns + x;
    }
}

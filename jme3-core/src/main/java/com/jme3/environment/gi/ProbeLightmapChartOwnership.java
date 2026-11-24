package com.jme3.environment.gi;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Finds the nearest UV island independently of its sampled lighting. */
final class ProbeLightmapChartOwnership {
    private ProbeLightmapChartOwnership() {
    }

    static int[] rasterize(Mesh mesh, int width, int height, int gutterRadius) {
        return rasterize(mesh, width, height, gutterRadius, null);
    }

    static int[] rasterize(Mesh mesh, int width, int height, int gutterRadius, boolean[] boundary) {
        VertexBuffer buffer = mesh.getBuffer(Type.TexCoord2);
        if (mesh.getMode() != Mesh.Mode.Triangles || buffer == null
                || !(buffer.getData() instanceof FloatBuffer) || buffer.getNumComponents() != 2
                || buffer.getStride() != 0 || buffer.getNumElements() != mesh.getVertexCount()) {
            return null;
        }
        FloatBuffer uv = (FloatBuffer) buffer.getData();
        int vertices = mesh.getVertexCount();
        for (int i = 0; i < vertices * 2; i++) {
            float coordinate = uv.get(i);
            if (!Float.isFinite(coordinate) || coordinate < 0f || coordinate > 1f) {
                return null;
            }
        }

        int[] parents = new int[vertices];
        for (int vertex = 0; vertex < vertices; vertex++) {
            parents[vertex] = vertex;
        }
        var indices = mesh.getIndicesAsList();
        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int a = root(parents, indices.get(i));
            int b = root(parents, indices.get(i + 1));
            int c = root(parents, indices.get(i + 2));
            parents[b] = a;
            parents[c] = a;
        }

        int[] owners = new int[width * height];
        Arrays.fill(owners, -1);
        float[] distance = new float[owners.length];
        Arrays.fill(distance, Float.POSITIVE_INFINITY);
        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int a = indices.get(i);
            int b = indices.get(i + 1);
            int c = indices.get(i + 2);
            float ax = uv.get(2 * a) * width;
            float ay = uv.get(2 * a + 1) * height;
            float bx = uv.get(2 * b) * width;
            float by = uv.get(2 * b + 1) * height;
            float cx = uv.get(2 * c) * width;
            float cy = uv.get(2 * c + 1) * height;
            float area = side(ax, ay, bx, by, cx, cy);
            if (area == 0f) {
                continue;
            }
            float orientation = Math.copySign(1f, area);
            int firstX = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx))) - gutterRadius);
            int lastX = Math.min(width - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))) + gutterRadius);
            int firstY = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy))) - gutterRadius);
            int lastY = Math.min(height - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))) + gutterRadius);
            int chart = root(parents, a);
            for (int y = firstY; y <= lastY; y++) {
                float py = y + .5f;
                for (int x = firstX; x <= lastX; x++) {
                    int pixel = y * width + x;
                    if (distance[pixel] == 0f) {
                        continue;
                    }
                    float px = x + .5f;
                    float ab = orientation * side(ax, ay, bx, by, px, py);
                    float bc = orientation * side(bx, by, cx, cy, px, py);
                    float ca = orientation * side(cx, cy, ax, ay, px, py);
                    float squared = 0f;
                    if (ab < 0f || bc < 0f || ca < 0f) {
                        squared = Math.min(segmentDistance(ax, ay, bx, by, px, py),
                                Math.min(segmentDistance(bx, by, cx, cy, px, py),
                                        segmentDistance(cx, cy, ax, ay, px, py)));
                    }
                    if (squared < distance[pixel]) {
                        distance[pixel] = squared;
                        owners[pixel] = chart;
                    }
                }
            }
        }
        if (boundary != null) {
            for (int i = 0; i < boundary.length; i++) {
                // A corner may reach a triangle even when all regular sample centers miss.
                boundary[i] = distance[i] <= .5f;
            }
        }
        return owners;
    }

    private static int root(int[] parents, int vertex) {
        while (parents[vertex] != vertex) {
            parents[vertex] = parents[parents[vertex]];
            vertex = parents[vertex];
        }
        return vertex;
    }

    private static float side(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static float segmentDistance(float ax, float ay, float bx, float by, float px, float py) {
        float dx = bx - ax;
        float dy = by - ay;
        float length = dx * dx + dy * dy;
        float t = length == 0f ? 0f : Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / length));
        float x = px - (ax + t * dx);
        float y = py - (ay + t * dy);
        return x * x + y * y;
    }
}

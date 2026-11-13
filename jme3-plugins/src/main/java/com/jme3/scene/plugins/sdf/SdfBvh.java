package com.jme3.scene.plugins.sdf;

import java.util.Arrays;
import java.util.Comparator;

/** CPU-only median-split BVH for exact triangle distances and parity ray crossings. */
final class SdfBvh {
    private static final double[][] DIRECTIONS = {{1, .371, .529}, {-.219, 1, .613}, {.417, -.327, 1}};
    private final double[][] triangles;
    final Node root;

    static final class Node {
        final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        int start;
        int end;
        Node left;
        Node right;
    }

    static final class Hits {
        double[] values = new double[32];
        int size;

        void add(double value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }
    }

    SdfBvh(SdfMeshSnapshot snapshot) {
        // Only reorder our private array; snapshot triangles themselves are immutable.
        triangles = snapshot.triangles.clone();
        root = build(0, triangles.length);
    }

    private Node build(int start, int end) {
        SdfMeshSnapshot.cancelled();
        Node node = new Node();
        node.start = start;
        node.end = end;
        for (int i = start; i < end; i++) {
            double[] triangle = triangles[i];
            for (int vertex = 0; vertex < 3; vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    node.min[axis] = Math.min(node.min[axis], triangle[vertex * 3 + axis]);
                    node.max[axis] = Math.max(node.max[axis], triangle[vertex * 3 + axis]);
                }
            }
        }
        if (end - start > 8) {
            int longest = 0;
            for (int axis = 1; axis < 3; axis++) {
                if (node.max[axis] - node.min[axis] > node.max[longest] - node.min[longest]) {
                    longest = axis;
                }
            }
            final int axis = longest;
            Arrays.sort(triangles, start, end, Comparator.comparingDouble(t -> t[axis] + t[axis + 3] + t[axis + 6]));
            int middle = (start + end) >>> 1;
            node.left = build(start, middle);
            node.right = build(middle, end);
        }
        return node;
    }

    double distanceSquared(double[] p) {
        return nearest(root, p, Double.POSITIVE_INFINITY);
    }

    private double nearest(Node node, double[] p, double best) {
        if (boxDistance(node, p) >= best) {
            return best;
        }
        if (node.left == null) {
            for (int i = node.start; i < node.end; i++) {
                best = Math.min(best, triangleDistance(triangles[i], p));
            }
            return best;
        }
        Node first = node.left;
        Node second = node.right;
        if (boxDistance(first, p) > boxDistance(second, p)) {
            first = node.right;
            second = node.left;
        }
        best = nearest(first, p, best);
        return nearest(second, p, best);
    }

    private static double boxDistance(Node node, double[] p) {
        double result = 0;
        for (int axis = 0; axis < 3; axis++) {
            double d = Math.max(Math.max(node.min[axis] - p[axis], p[axis] - node.max[axis]), 0);
            result += d * d;
        }
        return result;
    }

    boolean inside(double[] p, Hits hits, double epsilon) {
        int votes = 0;
        for (int direction = 0; direction < 3; direction++) {
            hits.size = 0;
            intersect(root, p, DIRECTIONS[direction], hits, epsilon);
            Arrays.sort(hits.values, 0, hits.size);
            int crossings = 0;
            double last = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < hits.size; i++) {
                double distance = hits.values[i];
                // A ray through a shared triangle edge intersects one surface, not two.
                if (distance - last > epsilon) {
                    crossings++;
                    last = distance;
                }
            }
            if ((crossings & 1) != 0) {
                votes++;
            }
            if (votes == 2) {
                return true;
            }
            if (direction == 1 && votes == 0) {
                return false;
            }
        }
        return false;
    }

    private void intersect(Node node, double[] p, double[] direction, Hits hits, double epsilon) {
        double near = 0;
        double far = Double.POSITIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            double a = (node.min[axis] - p[axis]) / direction[axis];
            double b = (node.max[axis] - p[axis]) / direction[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }
        if (far < near) {
            return;
        }
        if (node.left != null) {
            intersect(node.left, p, direction, hits, epsilon);
            intersect(node.right, p, direction, hits, epsilon);
            return;
        }
        for (int i = node.start; i < node.end; i++) {
            double distance = rayTriangle(triangles[i], p, direction);
            if (distance > epsilon) {
                hits.add(distance);
            }
        }
    }

    private static double rayTriangle(double[] t, double[] p, double[] d) {
        double ax = t[3] - t[0];
        double ay = t[4] - t[1];
        double az = t[5] - t[2];
        double bx = t[6] - t[0];
        double by = t[7] - t[1];
        double bz = t[8] - t[2];
        double hx = d[1] * bz - d[2] * by;
        double hy = d[2] * bx - d[0] * bz;
        double hz = d[0] * by - d[1] * bx;
        double determinant = ax * hx + ay * hy + az * hz;
        if (Math.abs(determinant) < Math.sqrt(areaSquared(t)) * 1e-12) {
            return -1;
        }
        double sx = p[0] - t[0];
        double sy = p[1] - t[1];
        double sz = p[2] - t[2];
        double u = (sx * hx + sy * hy + sz * hz) / determinant;
        if (u < -1e-10 || u > 1 + 1e-10) {
            return -1;
        }
        double qx = sy * az - sz * ay;
        double qy = sz * ax - sx * az;
        double qz = sx * ay - sy * ax;
        double v = (d[0] * qx + d[1] * qy + d[2] * qz) / determinant;
        if (v < -1e-10 || u + v > 1 + 1e-10) {
            return -1;
        }
        return (bx * qx + by * qy + bz * qz) / determinant;
    }

    static double areaSquared(double[] t) {
        double ax = t[3] - t[0];
        double ay = t[4] - t[1];
        double az = t[5] - t[2];
        double bx = t[6] - t[0];
        double by = t[7] - t[1];
        double bz = t[8] - t[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        return nx * nx + ny * ny + nz * nz;
    }

    private static double triangleDistance(double[] t, double[] p) {
        double ax = t[3] - t[0];
        double ay = t[4] - t[1];
        double az = t[5] - t[2];
        double bx = t[6] - t[0];
        double by = t[7] - t[1];
        double bz = t[8] - t[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double px = p[0] - t[0];
        double py = p[1] - t[1];
        double pz = p[2] - t[2];
        double aa = ax * ax + ay * ay + az * az;
        double ab = ax * bx + ay * by + az * bz;
        double bb = bx * bx + by * by + bz * bz;
        double pa = px * ax + py * ay + pz * az;
        double pb = px * bx + py * by + pz * bz;
        double normalSquared = nx * nx + ny * ny + nz * nz;
        double u = (pa * bb - pb * ab) / normalSquared;
        double v = (pb * aa - pa * ab) / normalSquared;
        if (u >= 0 && v >= 0 && u + v <= 1) {
            double plane = px * nx + py * ny + pz * nz;
            return plane * plane / normalSquared;
        }
        return Math.min(segmentDistance(t, 0, 3, p),
                Math.min(segmentDistance(t, 3, 6, p), segmentDistance(t, 6, 0, p)));
    }

    private static double segmentDistance(double[] t, int a, int b, double[] p) {
        double dx = t[b] - t[a];
        double dy = t[b + 1] - t[a + 1];
        double dz = t[b + 2] - t[a + 2];
        double px = p[0] - t[a];
        double py = p[1] - t[a + 1];
        double pz = p[2] - t[a + 2];
        double factor = (px * dx + py * dy + pz * dz) / (dx * dx + dy * dy + dz * dz);
        factor = Math.max(0, Math.min(1, factor));
        px -= factor * dx;
        py -= factor * dy;
        pz -= factor * dz;
        return px * px + py * py + pz * pz;
    }
}

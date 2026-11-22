/*
 * Copyright (c) 2009-2020 jMonkeyEngine
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
package com.jme3.scene.control;

import com.jme3.bounding.BoundingVolume;
import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.system.JmeSystem;
import com.jme3.system.Platform;
import com.jme3.util.clone.JmeCloneable;
import java.io.IOException;

/**
 * Determines what Level of Detail a spatial should be, based on how many pixels
 * on the screen the spatial is taking up. The more pixels covered, the more
 * detailed the spatial should be. It calculates the area of the screen that the
 * spatial covers by using its bounding volume. The camera projection and
 * triangles per pixel determine which available level to draw, subject to
 * the configured minimum level on desktop or mobile.
 */
public class LodControl extends AbstractControl implements Cloneable, JmeCloneable {

    private float trisPerPixel = 1f;
    private float distTolerance = 1f;
    private int desktopStartLevel;
    private int mobileStartLevel;
    private transient Mesh lastMesh;
    private transient int lastLevelCount;
    private transient float lastDistance = Float.NaN;
    private transient float lastWidth;
    private transient int lastLevel;

    private static class RuntimePlatform {
        private static final Platform PLATFORM = JmeSystem.getPlatform();
    }

    /**
     * Creates a new
     * <code>LodControl</code>.
     */
    public LodControl() {
    }

    /**
     * Returns the distance tolerance for changing LOD.
     *
     * @return the distance tolerance for changing LOD.
     *
     * @see #setDistTolerance(float)
     */
    public float getDistTolerance() {
        return distTolerance;
    }

    /**
     * Specifies the distance tolerance for changing the LOD level on the
     * geometry. The LOD level will only get changed if the geometry has moved
     * this distance beyond the current LOD level.
     *
     * @param distTolerance distance tolerance for changing LOD
     */
    public void setDistTolerance(float distTolerance) {
        this.distTolerance = distTolerance;
    }

    /**
     * Returns the triangles per pixel value.
     *
     * @return the triangles per pixel value.
     *
     * @see #setTrisPerPixel(float)
     */
    public float getTrisPerPixel() {
        return trisPerPixel;
    }

    /**
     * Sets the triangles per pixel value. The
     * <code>LodControl</code> will use this value as an error metric to
     * determine which LOD level to use based on the geometry's area on the
     * screen.
     *
     * @param trisPerPixel triangles per pixel
     */
    public void setTrisPerPixel(float trisPerPixel) {
        this.trisPerPixel = trisPerPixel;
        lastDistance = Float.NaN;
    }

    /** @return the finest level allowed on desktop, initially 0 */
    public int getDesktopStartLevel() {
        return desktopStartLevel;
    }

    /**
     * Sets the finest desktop level. The first and subsequent renders can
     * select coarser levels according to projected size. Out-of-range values
     * are clamped to the last available level at render time.
     * Change this on the render thread for a live scene.
     * <pre>{@code
     * // 第一步：加载带有 LodControl 的 J3O。
     * LodControl lod = geometry.getControl(LodControl.class);
     * // 第二步：PC 从 LOD0 开始，移动端从 LOD1 开始。
     * lod.setDesktopStartLevel(0);
     * lod.setMobileStartLevel(1);
     * // 第三步：两端都恢复完整细节时，将移动端起始级别设回 0。
     * lod.setMobileStartLevel(0);
     * }</pre>
     *
     * @param level nonnegative LOD index
     */
    public void setDesktopStartLevel(int level) {
        desktopStartLevel = validateStartLevel(level);
        lastDistance = Float.NaN;
    }

    /** @return the finest level allowed on Android and iOS, initially 0 */
    public int getMobileStartLevel() {
        return mobileStartLevel;
    }

    /**
     * Sets the finest level on Android and iOS, with the same clamping and
     * render-thread requirements as {@link #setDesktopStartLevel(int)}.
     *
     * @param level nonnegative LOD index
     */
    public void setMobileStartLevel(int level) {
        mobileStartLevel = validateStartLevel(level);
        lastDistance = Float.NaN;
    }

    private static int validateStartLevel(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("LOD start level must be nonnegative");
        }
        return level;
    }

    int startLevel(Platform platform) {
        Platform.Os os = platform.getOs();
        return os == Platform.Os.Android || os == Platform.Os.iOS ? mobileStartLevel : desktopStartLevel;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        if (spatial != null && !(spatial instanceof Geometry)) {
            throw new IllegalArgumentException("LodControl can only be attached to Geometry!");
        }

        super.setSpatial(spatial);
        lastMesh = null;
        lastDistance = Float.NaN;
    }

    @Override
    public Object jmeClone() {
        LodControl clone = (LodControl)super.jmeClone();
        clone.lastMesh = null;
        clone.lastDistance = Float.NaN;
        return clone;
    }     

    @Override
    protected void controlUpdate(float tpf) {
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        if (!(spatial instanceof Geometry)) {
            return;
        }
        Geometry geometry = (Geometry) spatial;
        Mesh mesh = geometry.getMesh();
        int levels = mesh == null ? 0 : mesh.getNumLodLevels();
        if (mesh != lastMesh || levels != lastLevelCount) {
            lastMesh = mesh;
            lastLevelCount = levels;
            lastDistance = Float.NaN;
        }
        BoundingVolume bound = geometry.getWorldBound();
        if (levels == 0 || bound == null) {
            return;
        }
        int minimum = Math.min(desktopStartLevel == mobileStartLevel
                ? desktopStartLevel : startLevel(RuntimePlatform.PLATFORM), levels - 1);
        Camera cam = vp.getCamera();
        float width = cam.getWidth() * (cam.getViewPortRight() - cam.getViewPortLeft());
        float horizontalSpan = cam.getFrustumRight() - cam.getFrustumLeft();
        float distance = cam.isParallelProjection() ? horizontalSpan * .5f
                : bound.distanceTo(cam.getLocation()) * horizontalSpan / (2f * cam.getFrustumNear());
        int level;
        if (width == lastWidth && lastLevel >= minimum && lastLevel < levels
                && distance > 0f && Math.abs(distance - lastDistance) <= distTolerance && !cam.isParallelProjection()) {
            level = lastLevel;
        } else {
            level = minimum;
            if (distance > 0f) {
                float triangleBudget = AreaUtils.calcScreenArea(bound, distance, width) * trisPerPixel;
                level = levels - 1;
                while (level > minimum && triangleBudget >= mesh.getTriangleCount(level - 1)) {
                    level--;
                }
            }
            lastDistance = distance;
            lastWidth = width;
        }
        lastLevel = level;
        if (geometry.getLodLevel() != level) {
            geometry.setLodLevel(level);
        }
    }

    @Override
    public void write(JmeExporter ex) throws IOException {
        super.write(ex);
        OutputCapsule oc = ex.getCapsule(this);
        oc.write(trisPerPixel, "trisPerPixel", 1f);
        oc.write(distTolerance, "distTolerance", 1f);
        oc.write(desktopStartLevel, "desktopStartLevel", 0);
        oc.write(mobileStartLevel, "mobileStartLevel", 0);
        // Older runtimes read these fields instead of querying the mesh.
        Mesh mesh = spatial instanceof Geometry ? ((Geometry) spatial).getMesh() : null;
        int levels = mesh == null ? 0 : mesh.getNumLodLevels();
        if (levels > 0) {
            int[] triangles = new int[levels];
            for (int i = 0; i < levels; i++) {
                triangles[i] = mesh.getTriangleCount(i);
            }
            oc.write(levels, "numLevels", 0);
            oc.write(triangles, "numTris", null);
        }
    }

    @Override
    public void read(JmeImporter im) throws IOException {
        super.read(im);
        InputCapsule ic = im.getCapsule(this);
        trisPerPixel = ic.readFloat("trisPerPixel", 1f);
        distTolerance = ic.readFloat("distTolerance", 1f);
        desktopStartLevel = Math.max(0, ic.readInt("desktopStartLevel", 0));
        mobileStartLevel = Math.max(0, ic.readInt("mobileStartLevel", 0));
        lastMesh = null;
        lastDistance = Float.NaN;
    }
}

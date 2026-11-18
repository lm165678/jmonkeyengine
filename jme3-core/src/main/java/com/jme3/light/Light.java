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
package com.jme3.light;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.export.*;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.scene.Spatial;
import com.jme3.util.TempVars;
import java.io.IOException;

/**
 * Abstract class for representing a light source.
 * <p>
 * All light source types have a color.
 */
public abstract class Light implements Savable, Cloneable {

    /**
     * Describes the light type.
     */
    public enum Type {

        /**
         * Directional light
         * 
         * @see DirectionalLight
         */
        Directional(0),
        
        /**
         * Point light
         * 
         * @see PointLight
         */
        Point(1),
        
        /**
         * Spot light.
         * 
         * @see SpotLight
         */
        Spot(2),
        
        /**
         * Ambient light
         * 
         * @see AmbientLight
         */
        Ambient(3),
        
        /**
         * Light probe
         * @see LightProbe
         */
        Probe(4),

        /** Distant sky radiance, independent of local reflection selection. */
        Sky(5);
                

        private final int typeId;

        Type(int type){
            this.typeId = type;
        }

        /**
         * Returns an index for the light type
         * @return an index for the light type
         */
        public int getId(){
            return typeId;
        }
    }

    protected ColorRGBA color = new ColorRGBA(ColorRGBA.White);
    
    /**
     * Used in LightList for caching the distance 
     * to the owner spatial. Should be reset after the sorting.
     */
    protected transient float lastDistance = -1;

    protected boolean enabled = true;

    private boolean castShadow = true;
    private com.jme3.shadow.LightShadowSettings shadowSettings = new com.jme3.shadow.LightShadowSettings();

    /** 
     * The light name. 
     */
    protected String name;
    protected boolean global = false;
    
    boolean frustumCheckNeeded = true;
    boolean intersectsFrustum  = false;

    protected Light() {
    }


    protected Light(boolean global) {
        this.global = global;
    }
    
    protected Light(ColorRGBA color, boolean global) {
        setColor(color);
        this.global = global;
    }

    protected Light(ColorRGBA color) {
        setColor(color);
    }


    /**
     * Returns true if this light affects the entire tree from the root node,
     * otherwise returns false, meaning it only affects the children of the node
     * in which it is attached.
     * @return true if the light is global, otherwise false.
     */
    public boolean isGlobal() {
        return global;
    }

    /**
     * Returns the color of the light.
     * 
     * @return The color of the light.
     */
    public ColorRGBA getColor() {
        return color;
    }

    /**
     * This method sets the light name.
     * 
     * @param name the light name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Return the light name.
     * 
     * @return the light name
     */
    public String getName() {
        return name;
    }

    /*
    public void setLastDistance(float lastDistance){
        this.lastDistance = lastDistance;
    }

    public float getLastDistance(){
        return lastDistance;
    }
    */

    /**
     * Sets the light color.
     * 
     * @param color the light color.
     */
    public void setColor(ColorRGBA color){
        this.color.set(color);
    }


    /**
     * Returns true if this light is enabled.
     * @return true if enabled, otherwise false.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Set to false in order to disable a light and have it filtered out from being included in rendering.
     *
     * @param enabled true to enable and false to disable the light.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Scene-owned shadow settings, serialized with this light and cloned independently.
     * Defaults: 512 pixels, three directional cascades and 3x3 PCF. Change on the
     * render thread before drawing. Viewport overrides take precedence; clearSettings
     * restores these preferences. Ambient/probe lights do not create shadow maps.
     * Legacy shadow processors keep their own settings.
     * <pre>{@code
     * // 第一步：光源配置随场景保存。
     * sun.getShadowSettings().setMapSize(1024);
     * sun.getShadowSettings().setCascadeCount(4);
     * sun.getShadowSettings().setFilterRadius(2);
     * // 第二步：临时视口覆盖不会修改场景配置。
     * renderManager.getShadowManager(viewPort).getSettings(sun).setMapSize(512);
     * // 第三步：恢复光源自身配置。
     * renderManager.getShadowManager(viewPort).clearSettings(sun);
     * }</pre>
     * @return mutable scene-owned settings
     */
    public com.jme3.shadow.LightShadowSettings getShadowSettings() {
        return shadowSettings;
    }

    /**
     * Returns the serialized shadow-casting preference, initially true. Ambient
     * lights and reflection probes do not produce shadow maps, regardless of this
     * preference. This flag does not allocate shadow resources by itself.
     *
     * @return whether shadow casting is requested
     */
    public boolean isCastShadow() {
        return castShadow;
    }

    /**
     * Sets the scene-owned shadow preference without disabling illumination.
     * Viewport shadow policies may additionally disable shadow casting. Change
     * this on the application render thread, or through Application.enqueue().
     * Older assets without this field default to true; cloning preserves it.
     * Built-in ShadowRenderer and ShadowFilter implementations also honor this
     * flag, retaining their resources for re-enabling. Custom processors must
     * explicitly honor it. The built-in viewport pipeline discovers enabled
     * directional, point and spot lights automatically through ShadowManager;
     * this setter does not install a legacy processor or allocate GPU resources.
     *
     * <pre>{@code
     * // 第一步：创建光源，默认请求投影。
     * DirectionalLight sun = new DirectionalLight();
     * rootNode.addLight(sun);
     * // 第二步：关闭投影，但保留照明；此设置随场景保存。
     * sun.setCastShadow(false);
     * // 第三步：重新允许投影，实际资源由视口阴影路径管理。
     * sun.setCastShadow(true);
     * }</pre>
     *
     * @param castShadow true to request shadows, false to disable them
     */
    public void setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
    }

    public boolean isFrustumCheckNeeded() {
      return frustumCheckNeeded;
    }

    public void setFrustumCheckNeeded(boolean frustumCheckNeeded) {
      this.frustumCheckNeeded = frustumCheckNeeded;
    }

    public boolean isIntersectsFrustum() {
      return intersectsFrustum;
    }

    public void setIntersectsFrustum(boolean intersectsFrustum) {
      this.intersectsFrustum = intersectsFrustum;
    }
    
    /**
     * Determines if the light intersects with the given bounding box.
     * <p>
     * For non-local lights, such as {@link DirectionalLight directional lights},
     * {@link AmbientLight ambient lights}, or {@link PointLight point lights}
     * without influence radius, this method should always return true.
     * 
     * @param box The box to check intersection against.
     * @param vars TempVars in case it is needed.
     * 
     * @return True if the light intersects the box, false otherwise.
     */
    public abstract boolean intersectsBox(BoundingBox box, TempVars vars);
    
    /**
     * Determines if the light intersects with the given bounding sphere.
     * <p>
     * For non-local lights, such as {@link DirectionalLight directional lights},
     * {@link AmbientLight ambient lights}, or {@link PointLight point lights}
     * without influence radius, this method should always return true.
     * 
     * @param sphere The sphere to check intersection against.
     * @param vars TempVars in case it is needed.
     * 
     * @return True if the light intersects the sphere, false otherwise.
     */
    public abstract boolean intersectsSphere(BoundingSphere sphere, TempVars vars);

    /**
     * Determines if the light intersects with the given camera frustum.
     * 
     * For non-local lights, such as {@link DirectionalLight directional lights},
     * {@link AmbientLight ambient lights}, or {@link PointLight point lights}
     * without influence radius, this method should always return true.
     * 
     * @param camera The camera frustum to check intersection against.
     * @param vars TempVars in case it is needed.
     * @return True if the light intersects the frustum, false otherwise.
     */
    public abstract boolean intersectsFrustum(Camera camera, TempVars vars);
    
    @Override
    public Light clone(){
        try {
            Light l = (Light) super.clone();
            l.color = color.clone();
            l.shadowSettings = shadowSettings.clone();
            return l;
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError();
        }
    }

    @Override
    public void write(JmeExporter ex) throws IOException {
        OutputCapsule oc = ex.getCapsule(this);
        oc.write(color, "color", null);
        oc.write(enabled, "enabled", true);
        oc.write(castShadow, "castShadow", true);
        oc.write(shadowSettings, "shadowSettings", null);
        oc.write(name, "name", null);
        oc.write(global, "global", false);
    }

    @Override
    public void read(JmeImporter im) throws IOException {
        InputCapsule ic = im.getCapsule(this);
        color = (ColorRGBA) ic.readSavable("color", null);
        enabled = ic.readBoolean("enabled", true);
        castShadow = ic.readBoolean("castShadow", true);
        shadowSettings = (com.jme3.shadow.LightShadowSettings) ic.readSavable("shadowSettings",
                new com.jme3.shadow.LightShadowSettings());
        name = ic.readString("name", null);
        global = ic.readBoolean("global", false);
    }

    /**
     * Used internally to compute the last distance value.
     *
     * @param owner the Spatial whose distance is to be determined
     */
    protected abstract void computeLastDistance(Spatial owner);
    
    /**
     * Returns the light type
     * 
     * @return the light type
     * 
     * @see Type
     */
    public abstract Type getType();

}

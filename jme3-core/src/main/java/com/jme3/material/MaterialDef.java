/*
 * Copyright (c) 2009-2019 jMonkeyEngine
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
package com.jme3.material;

import com.jme3.asset.AssetManager;
import com.jme3.shader.VarType;
import com.jme3.texture.image.ColorSpace;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.jme3.texture.Texture;

/**
 * Describes a J3MD (Material definition).
 * 
 * @author Kirill Vainer
 */
public class MaterialDef{

    private static final Logger logger = Logger.getLogger(MaterialDef.class.getName());

    private String name;
    private String assetName;
    private int shadingId;
    private AssetManager assetManager;

    private Map<String, List<TechniqueDef>> techniques;
    private Map<String, MatParam> matParams;

    /**
     * Serialization only. Do not use.
     */
    protected MaterialDef(){
    }
    
    /**
     * Creates a new material definition with the given name.
     * 
     * @param assetManager The asset manager to use to load shaders
     * @param name The debug name of the material definition
     */
    public MaterialDef(AssetManager assetManager, String name){
        this.assetManager = assetManager;
        this.name = name;
        techniques = new HashMap<String, List<TechniqueDef>>();
        matParams = new HashMap<String, MatParam>();
        logger.log(Level.FINE, "Loaded material definition: {0}", name);
    }

    /**
     * Returns the asset key name of the asset from which this material 
     * definition was loaded.
     * 
     * @return Asset key name of the j3md file 
     */
    public String getAssetName() {
        return assetName;
    }

    /**
     * Returns the deferred shading model identifier. Zero opts out of deferred
     * shading, preserving the existing forward rendering path.
     * @return shading model identifier, or zero for forward rendering
     */
    public int getShadingModelId() { return shadingId; }

    public void setShadingModelId(int id) { setShadingId(id); }

    public void setShadingModel(ShadingModel model) { setShadingId(model.getId()); }

    public int getShadingId() {
        return shadingId;
    }

    /**
     * Selects a deferred shading model. The renderer must also recognize the
     * identifier and the material must provide a compatible G-buffer technique.
     * @param shadingId identifier in the unsigned 16-bit range; zero opts out
     */
    public void setShadingId(int shadingId) {
        if (shadingId < 0 || shadingId > 65535) {
            throw new IllegalArgumentException("ShadingId must be between 0 and 65535");
        }
        this.shadingId = shadingId;
    }

    /**
     * Set the asset key name. 
     * 
     * @param assetName the asset key name
     */
    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    /**
     * Returns the AssetManager passed in the constructor.
     * 
     * @return the AssetManager passed in the constructor.
     */
    public AssetManager getAssetManager(){
        return assetManager;
    }

    /**
     * The debug name of the material definition.
     * 
     * @return debug name of the material definition.
     */
    public String getName(){
        return name;
    }

    /**
     * Adds a new material parameter.
     * 
     * @param type Type of the parameter
     * @param name Name of the parameter
     * @param value Default value of the parameter
     */
    public void addMaterialParam(VarType type, String name, Object value) {
        matParams.put(name, new MatParam(type, name, value));
    }
    
    /**
     * Adds a new material parameter.
     * 
     * @param type Type of the parameter
     * @param name Name of the parameter
     * @param colorSpace the color space of the texture required by this texture param
     * @param value Default value of the parameter
     * @see ColorSpace
     */
    public void addMaterialParamTexture(VarType type, String name, ColorSpace colorSpace,Texture value) {
        matParams.put(name, new MatParamTexture(type, name, value, colorSpace));
    }
    
    /**
     * Returns the material parameter with the given name.
     * 
     * @param name The name of the parameter to retrieve
     * 
     * @return The material parameter, or null if it does not exist.
     */
    public MatParam getMaterialParam(String name){
        return matParams.get(name);
    }
    
    /**
     * Returns a collection of all material parameters declared in this
     * material definition.
     * <p>
     * Modifying the material parameters or the collection will lead
     * to undefined results.
     * 
     * @return All material parameters declared in this definition.
     */
    public Collection<MatParam> getMaterialParams(){
        return matParams.values();
    }

    /**
     * Adds a new technique definition to this material definition.
     *
     * @param technique The technique definition to add.
     */
    public void addTechniqueDef(TechniqueDef technique) {
        List<TechniqueDef> list = techniques.get(technique.getName());
        if (list == null) {
            list = new ArrayList<>();
            techniques.put(technique.getName(), list);
        }
        list.add(technique);
    }

    /**
     * Returns technique definitions with the given name.
       * 
     * @param name The name of the technique definitions to find
       * 
     * @return The technique definitions, or null if cannot be found.
     */
    public List<TechniqueDef> getTechniqueDefs(String name) {
        return techniques.get(name);
    }

    /**
     *
     * @return the list of all the technique definitions names.
     */
    public Collection<String> getTechniqueDefsNames(){
        return techniques.keySet();
    }

}

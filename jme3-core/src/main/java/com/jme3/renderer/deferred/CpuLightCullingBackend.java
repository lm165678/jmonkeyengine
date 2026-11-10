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

import com.jme3.light.Light;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Limits;
import com.jme3.texture.Image;
import java.nio.ByteBuffer;
import java.util.List;

final class CpuLightCullingBackend implements LightCullingBackend {
    private TileLightGrid grid;
    @Override public void reset() { grid = null; }
    @Override public boolean prepare(DeferredRenderContext frame) {
        Camera camera = frame.camera();
        List<Light> lights = frame.lights;
        DeferredResources r = frame.resources;
        int maxTexture = frame.renderer.getLimits().get(Limits.TextureSize);
        if (grid == null || grid.getTileSize() != frame.tileSize) grid = new TileLightGrid(frame.tileSize);
        grid.build(camera,lights);
        int tileCount=grid.getColumns()*grid.getRows();
        long entries=tileCount*2L;
        for(int y=0;y<grid.getRows();y++) for(int x=0;x<grid.getColumns();x++) entries+=grid.getLightCount(x,y);
        if(entries>Math.min(16777216L,(long)maxTexture*maxTexture)) {
            frame.fallbackReason="CPU light index capacity exceeded; retaining forward queue"; return false;
        }
        int width = Math.min(maxTexture, Math.max(1024, (int)((entries + maxTexture - 1) / maxTexture)));
        int height = ((int)entries + width - 1) / width;
        r.tileTexture=r.storage(r.tileTexture,width,height,Image.Format.R32F,width*height*4);
        ByteBuffer data=r.tileTexture.getImage().getData(0);
        int offset=tileCount*2;
        for(int y=0;y<grid.getRows();y++) for(int x=0;x<grid.getColumns();x++) {
            int tile=y*grid.getColumns()+x,count=grid.getLightCount(x,y);
            data.putFloat(tile*8,offset);data.putFloat(tile*8+4,count);
            for(int i=0;i<count;i++) data.putFloat(offset++*4,grid.getLightIndex(x,y,i));
        }
        data.clear();r.tileTexture.getImage().setUpdateNeeded();return true;
    }
}

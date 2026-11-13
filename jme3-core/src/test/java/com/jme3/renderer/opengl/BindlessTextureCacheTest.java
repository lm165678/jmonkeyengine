package com.jme3.renderer.opengl;

import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import org.junit.Test;
import static org.junit.Assert.*;

public class BindlessTextureCacheTest {
    @Test
    public void renderTargetLookupNeverHashesPixelContents() {
        Image image = new Image() {
            @Override
            public int hashCode() {
                throw new AssertionError("Image content hash must not run on the draw path");
            }
        };
        BindlessTextureCache cache = new BindlessTextureCache(null);
        cache.renderTarget(image);
        cache.renderTarget(image);
        Texture2D texture = new Texture2D();
        texture.setImage(image);
        assertEquals(0, cache.acquire(texture));
        cache.close(true);
    }

    @Test
    public void contentInvalidationTracksEditsAfterUploadFlagIsCleared() {
        Image image = new Image();
        long revision = image.getContentRevision();
        image.setUpdateNeeded();
        image.clearUpdateNeeded();
        assertTrue(image.getContentRevision() > revision);
        assertFalse(image.isUpdateNeeded());
    }
}

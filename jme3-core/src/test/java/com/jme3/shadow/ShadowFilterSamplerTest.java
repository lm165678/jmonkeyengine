package com.jme3.shadow;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.texture.Texture;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowFilterSamplerTest {
    @Test
    public void softModesUseLinearHardwareComparisonRegardlessOfSetterOrder() {
        DirectionalLightShadowRenderer renderer = new DirectionalLightShadowRenderer(
                new DesktopAssetManager(true), 64, 1);
        for (EdgeFilteringMode mode : new EdgeFilteringMode[]{EdgeFilteringMode.Bilinear,
                EdgeFilteringMode.PCF4, EdgeFilteringMode.PCF8, EdgeFilteringMode.PCFPOISSON}) {
            renderer.setShadowCompareMode(CompareMode.Software);
            renderer.setEdgeFilteringMode(mode);
            assertEquals(Texture.MagFilter.Nearest, renderer.shadowMaps[0].getMagFilter());
            renderer.setShadowCompareMode(CompareMode.Hardware);
            assertEquals(Texture.MagFilter.Bilinear, renderer.shadowMaps[0].getMagFilter());
            renderer.setEdgeFilteringMode(EdgeFilteringMode.Nearest);
            assertEquals(Texture.MagFilter.Nearest, renderer.shadowMaps[0].getMagFilter());
            renderer.setEdgeFilteringMode(mode);
            assertEquals(Texture.MagFilter.Bilinear, renderer.shadowMaps[0].getMagFilter());
        }
    }
}

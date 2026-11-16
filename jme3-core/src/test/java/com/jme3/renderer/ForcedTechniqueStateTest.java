package com.jme3.renderer;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.NullRenderer;
import java.util.EnumSet;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForcedTechniqueStateTest {
    @Test
    public void passBiasOverridesLegacyMaterialOffsetOnlyWhenRequested() {
        final RenderState observed = new RenderState();
        NullRenderer renderer = new NullRenderer() {
            @Override
            public EnumSet<Caps> getCaps() {
                return EnumSet.of(Caps.GLSL100);
            }

            @Override
            public void applyRenderState(RenderState state) {
                observed.copyFrom(state);
            }
        };
        RenderManager manager = new RenderManager(renderer);
        manager.setCamera(new Camera(32, 32), false);
        Geometry box = new Geometry("caster", new Box(1, 1, 1));
        box.setMaterial(new Material(new DesktopAssetManager(true),
                "Common/MatDefs/Light/Lighting.j3md"));
        box.updateGeometricState();
        RenderState pass = new RenderState();
        pass.setPolyOffset(0, 0);
        manager.setForcedTechnique("PreShadow");
        manager.setForcedRenderState(pass);
        manager.renderGeometry(box);
        assertEquals(5f, observed.getPolyOffsetFactor(), 0f);
        manager.setForcedRenderStateOverridesTechnique(true);
        manager.renderGeometry(box);
        assertEquals(0f, observed.getPolyOffsetFactor(), 0f);
        assertSame(pass, manager.getForcedRenderState());
        manager.setForcedRenderStateOverridesTechnique(false);
        manager.renderGeometry(box);
        assertEquals(5f, observed.getPolyOffsetFactor(), 0f);
        manager.setForcedRenderStateOverridesTechnique(true);
        manager.setForcedRenderState(null);
        manager.renderGeometry(box);
        assertEquals(5f, observed.getPolyOffsetFactor(), 0f);
    }
}

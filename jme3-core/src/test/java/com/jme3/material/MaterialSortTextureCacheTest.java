package com.jme3.material;

import com.jme3.shader.VarType;
import com.jme3.texture.*;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialSortTextureCacheTest {
    private void select(Material material) throws Exception {
        Field field = Material.class.getDeclaredField("technique");
        field.setAccessible(true);
        field.set(material, new Technique(material, new TechniqueDef("sort", 1)));
        Field id = Material.class.getDeclaredField("sortingId");
        id.setAccessible(true);
        id.setInt(material, -1);
    }

    private Texture2D texture(int id) {
        Image image = new Image();
        image.setId(id);
        Texture2D texture = new Texture2D();
        texture.setImage(image);
        return texture;
    }

    @Test
    public void textureAddRemoveReplacementUploadAndCloneKeepOriginalSortEquation() throws Exception {
        MaterialDef definition = new MaterialDef(null, "sort");
        definition.addMaterialParamTexture(VarType.Texture2D, "A", null, null);
        definition.addMaterialParamTexture(VarType.Texture2D, "B", null, null);
        definition.addMaterialParam(VarType.Float, "Value", 1f);
        Material material = new Material(definition);
        Texture2D first = texture(3);
        material.setTexture("A", first);
        for (int operation = 0; operation < 6; operation++) {
            if (operation == 1) {
                material.setTexture("B", texture(9));
            } else if (operation == 2) {
                material.clearParam("A");
            } else if (operation == 3) {
                material = material.clone();
                material.setTexture("A", first);
            } else if (operation == 4) {
                first.getImage().resetObject();
                first.getImage().setId(31);
            } else if (operation == 5) {
                material.setTexture("B", texture(21));
            }
            select(material);
            int expected = material.getActiveTechnique().getSortId() << 16;
            int textures = 17;
            for (int i = 0; i < material.getParamsMap().size(); i++) {
                MatParam parameter = material.getParamsMap().getValue(i);
                if (parameter.getVarType().isTextureType()) {
                    int id = ((Texture) parameter.getValue()).getImage().getId();
                    textures = textures * 23 + Math.max(0, id);
                }
            }
            assertEquals(expected | textures & 0xffff, material.getSortId());
        }
    }
}

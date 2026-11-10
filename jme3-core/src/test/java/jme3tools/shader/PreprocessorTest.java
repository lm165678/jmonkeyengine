package jme3tools.shader;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreprocessorTest {
    @Test public void expandsTerrainLayerDeclarationsAndGuards() {
        String expanded = Preprocessor.forMacro("#for i=0..3 (#ifdef MAP_$i $0 #endif)\n"
                + "uniform sampler2D m_Map_$i;\n#endfor\n");
        for (int i = 0; i < 3; i++) {
            assertTrue(expanded.contains("#ifdef MAP_" + i));
            assertTrue(expanded.contains("uniform sampler2D m_Map_" + i + ";"));
        }
        assertFalse(expanded.contains("$"));
        assertFalse(expanded.contains("#for"));
    }
}

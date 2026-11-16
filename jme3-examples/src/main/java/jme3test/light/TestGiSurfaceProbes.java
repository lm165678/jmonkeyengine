package jme3test.light;

/**
 * Runnable surface-probe bake, J3O and automatic paged-streaming GPU regression.
 * Entry main; no input controls. Requires OpenGL 4.3 and float render targets.
 * Uses GiProbeVolume.setSurfaceLayout, GiAdaptiveBakeJob and automatic scene services.
 * Run {@code gradlew :jme3-examples:giSurfaceProbesSmoke}; success prints GI_SURFACE_PASS after
 * four rendering paths, Lighting/PBR and manual/automatic binding matrices.
 * <pre>
 * 第一步：运行本类 main（-ea），以离屏窗口烘焙表面自由探针。
 * 第二步：自动保存/重载 J3O，验证实际位置和布局模式保留。
 * 第三步：逐个切换四种管线及材质模型，最后仅挂载 J3O 验证自动流送。
 * 正常项目只需 volume.setSurfaceLayout(true)，烘焙并保存；不需手动绑定。
 * 默认仍是规则布局；恢复 false 后重新烘焙。表面模式在支持范围外返回零贡献。
 * </pre>
 */
public final class TestGiSurfaceProbes {
    private TestGiSurfaceProbes() {
    }

    public static void main(String[] args) {
        System.setProperty("jme.test.surfaceProbes", "true");
        TestGiAdaptiveProbes.main(args);
    }
}

package jme3test.gpuDrivenRendering;

/**
 * Equivalent-material merging with 300,000 Tank/Boat/Buggy instances. Uses
 * separate cloned materials with equal parameters, plus genuinely different
 * Lighting/PBR/Unshaded materials which must remain separate. Requires OpenGL 4.3.
 * Entry: TestMaterialMerging.main. Run gpuMaterialMerge; verify gpuMaterialMergeCheck
 * in jme3-examples (add -PgpuDeferred for Deferred). B toggles material merging,
 * M motion, Space GPU Scene, drag+WASD camera. Compares image, visible workload and
 * draw submissions. Source materials are never replaced or modified by batching.
 * <pre>{@code
 * // 第一步：独立材质实例可以具有相同参数，不要求调用者共享同一个对象。
 * geometryB.setMaterial(geometryA.getMaterial().clone());
 * // 第二步：启用通用材质等价合并，可与跨网格 MDI 组合。
 * pipeline.setMultiDrawIndirect(true);
 * pipeline.setMeshMerging(true);
 * pipeline.setMaterialMerging(true);
 * // 第三步：源材质通过 setter 修改后自动重新分组；直接修改内部数据需刷新。
 * pipeline.refreshSubtree(geometryB);
 * // 第四步：在渲染线程关闭对照；不同参数、纹理和状态始终保持独立。
 * pipeline.setMaterialMerging(false);
 * }</pre>
 */
public final class TestMaterialMerging {
    private TestMaterialMerging() {
    }

    public static void main(String[] args) {
        String[] options = java.util.Arrays.copyOf(args, args.length + 1);
        options[args.length] = "--material-merging";
        GpuDrivenFeatureBenchmark.launch(false, options);
    }
}

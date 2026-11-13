package jme3test.sdf;

/**
 * Tank texture-backed capture: base color, packed metallic/roughness, normal and emission.
 * Run gradlew :jme3-examples:surfaceMaterialMaps; append Smoke to run GPU checks.
 * Controls: 1/2/3 material model, C tint, R recapture, N normal map, E emission map. OpenGL 3.3 required.
 * Uses the original Tank textures; the two depth queries
 * test captured surface acceptance and rejection of a query behind it.
 *
 * <pre>{@code
 * // 第一步：使用原有材质参数，不另写一套 CPU 贴图采样逻辑。
 * material.setTexture("BaseColorMap", colorTexture);
 * material.setTexture("NormalMap", normalTexture);
 * material.setTexture("EmissiveMap", emissionTexture);
 * // 第二步：网格需要有效切线，法线贴图才能沿原材质路径正确计算。
 * TangentBinormalGenerator.generate(geometry);
 * // 第三步：渲染线程视口边界，一次生成布局和所有方向的属性；不生成 SDF。
 * SurfaceBakeData data = SurfaceAttributeBaker.bake(
 *     geometry, dependencyRevision, renderManager, viewPort, 128, null);
 * data.attachTo(geometry);
 * // 第四步：贴图内容改变时更新 SDK 外部依赖版本，并重新调用 bake 后另存 j3o。
 * // SurfaceCapture 使用同一材质的贴图参数；保存的是属性像素而不是光照结果。
 * // 未覆盖区域 depth=1 / coverage alpha=0，不能成为有效的材质查询命中。
 * }</pre>
 */
public final class TestSurfaceMaterialMaps extends TestSurfaceAttributes {
    public static void main(String[] args) {
        TestSurfaceMaterialMaps app = new TestSurfaceMaterialMaps();
        app.materialMaps = true;
        launch(app, args);
    }
}

package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.bounding.BoundingBox;
import com.jme3.font.BitmapText;
import com.jme3.light.DirectionalLight;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.SceneSurfaceQuery;
import com.jme3.scene.plugins.sdf.SurfaceBakeData;
import com.jme3.system.AppSettings;

/**
 * Exact reference ray + saved surface query on BakeTank; no DDGI or GPU-driven draw.
 * Run gradlew :jme3-examples:surfaceQuerySmoke for automated coverage checks.
 * Interactive entry: TestSurfaceQuery.main with no arguments. Drag+WASD moves
 * the camera; its center ray reports geometry hit and independent surface validity.
 * Requires existing BakeTank.j3o and OpenGL for ordinary model display, not queries.
 * <pre>{@code
 * // 第一步：读取独立烘焙属性，验证资源依赖后添加到场景。
 * Spatial model = assetManager.loadModel("Models/Tank/BakeTank.j3o");
 * SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * if (data.validate(model, "tank-demo-v1") != SurfaceBakeData.Status.VALID) {
 *     throw new IllegalStateException("Stale bake");
 * }
 * rootNode.attachChild(model);
 * rootNode.updateGeometricState();
 * // 第二步：每次场景更新完成后查询真实几何，不把距离场调试轮廓当精确命中。
 * SceneSurfaceQuery query = new SceneSurfaceQuery();
 * SceneSurfaceQuery.Hit hit = query.trace(rootNode, cam.getLocation(), cam.getDirection(), 1000);
 * // 第三步：独立检查属性有效性。相同命中可以有几何，但没有属性覆盖。
 * SceneSurfaceQuery.Surface surface = query.sample(hit, 0.1f);
 * // 第四步：业务必须分别处理 MISSING_DATA、STALE_DATA、UNCOVERED，不能当无障碍。
 * // 在场景线程调用；本参考查询不拥有 GPU 资源，停止使用即可，无需 close。
 * }</pre>
 * <p>GPU card lookup integration (GLSL 150+, material-owned fragment shader):</p>
 * <pre>{@code
 * // 第一步：j3md 声明 FragmentShader GLSL150，并由 frag 导入库：
 * #import "Common/ShaderLib/SurfaceAtlasLookup.glsllib"
 * // 第二步：CPU/未来 GPU 射线查询先确定命中的 Geometry，只尝试它的卡片。
 * // worldToCard 来自 atlas.getWorldToCard(instance, card)，cardToWorld 是其逆矩阵。
 * // rect 来自当前有效 Region.getUvTransform()；失效/未驻留时 resident=false。
 * // normalToWorld 是 Geometry 世界矩阵的逆转置 3x3，不能只使用旋转。
 * QueriedSurface surface;
 * int status = querySurfaceAtlasCard(worldHit, worldToCard, cardToWorld,
 *     normalToWorld, resident, rect, worldTolerance,
 *     diffuseAtlas, normalAtlas, emissionAtlas, depthAtlas, surface);
 * // 第三步：只有 SURFACE_QUERY_VALID 才读取属性；否则尝试同 Geometry 的其他卡片。
 * // NOT_RESIDENT 与 UNCOVERED 不等于射线 MISS，业务必须保持遮挡状态。
 * // 该函数只查一个卡片，不负责场景追踪；没有隐式全场景/材质注册。
 * }</pre>
 */
public final class TestSurfaceQuery extends SimpleApplication {
    private final SceneSurfaceQuery query = new SceneSurfaceQuery();
    private BitmapText text;
    private float tolerance;
    private boolean smoke;

    public static void main(String[] args) {
        TestSurfaceQuery app = new TestSurfaceQuery();
        app.smoke = java.util.Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1000, 700);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        Spatial model = assetManager.loadModel("Models/Tank/BakeTank.j3o");
        SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
        if (data.validate(model, "tank-demo-v1") != SurfaceBakeData.Status.VALID) {
            throw new IllegalStateException("Stale bake");
        }
        model.setLocalScale(1.2f, .8f, 1.1f);
        model.rotate(.1f, .4f, 0);
        rootNode.attachChild(model);
        rootNode.updateGeometricState();
        BoundingBox bounds = (BoundingBox) model.getWorldBound();
        Vector3f center = bounds.getCenter();
        float radius = bounds.getExtent(null).length();
        tolerance = radius / 64f;
        cam.setFrustumFar(radius * 20);
        cam.setLocation(center.add(0, radius, radius * 2));
        cam.lookAt(center, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(radius);
        flyCam.setDragToRotate(true);
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -1, -1).normalizeLocal()));
        text = new BitmapText(guiFont);
        text.setLocalTranslation(10, 680, 0);
        guiNode.attachChild(text);
        if (smoke) {
            int hits = 0;
            int covered = 0;
            for (int face = 0; face < 6; face++) {
                Vector3f axis = new Vector3f();
                axis.set(face / 2, face % 2 == 0 ? 1 : -1);
                Vector3f origin = center.add(axis.mult(radius * 3));
                for (int i = 0; i < 49; i++) {
                    Vector3f target = center.add((i % 7 - 3) * radius / 10, 0, (i / 7 - 3) * radius / 10);
                    SceneSurfaceQuery.Hit hit = query.trace(rootNode, origin, target.subtract(origin), radius * 10);
                    if (hit.status == SceneSurfaceQuery.HitStatus.HIT) {
                        hits++;
                        if (query.sample(hit, tolerance).status == SceneSurfaceQuery.SurfaceStatus.VALID) {
                            covered++;
                        }
                    }
                }
            }
            if (hits == 0 || covered == 0) {
                throw new AssertionError("No valid tank hits/coverage: " + hits + "/" + covered);
            }
            System.out.println("SURFACE_QUERY_PASS hits=" + hits + " covered=" + covered);
            stop();
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        rootNode.updateGeometricState();
        SceneSurfaceQuery.Hit hit = query.trace(rootNode, cam.getLocation(), cam.getDirection(), cam.getFrustumFar());
        SceneSurfaceQuery.Surface surface = query.sample(hit, tolerance);
        text.setText("CPU reference | hit=" + hit.status + " surface=" + surface.status
                + " card=" + surface.card + " distance=" + hit.distance + "\nDrag + WASD: center ray");
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }
}

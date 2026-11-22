package com.jme3.anim.graph;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;

/**
 * An actor-local planar displacement and Y-axis rotation produced during one
 * animation update. Positive yaw uses the engine's right-handed rotation.
 *
 * <pre>{@code
 * // 第一步：把 J3O 的根运动设为 Extract Only，加载角色。
 * AnimationGraphPlayer player = AnimationGraphPlayer.attach(actor);
 * // 第二步：在角色控制器中接收局部增量，由物理步处理碰撞和移动。
 * player.setRootMotionConsumer(delta -> controller.queueMovement(delta.getTranslation(), delta.getYaw()));
 * // 第三步：也可在每次逻辑更新后通过 getRootMotionDelta() 读取本帧增量。
 * // 第四步：普通节点用 Auto Apply，或在回调中对非物理父节点调用 delta.applyTo(wrapper)。
 * }</pre>
 */
public final class RootMotionDelta {
    static final RootMotionDelta ZERO = new RootMotionDelta(0, 0, 0);
    final double x;
    final double z;
    final double yaw;

    RootMotionDelta(double x, double z, double yaw) {
        this.x = x;
        this.z = z;
        this.yaw = yaw;
    }

    /** @return a new local displacement vector, with vertical motion omitted */
    public Vector3f getTranslation() {
        return new Vector3f((float) x, 0f, (float) z);
    }

    /** @return yaw in radians */
    public float getYaw() {
        return (float) yaw;
    }

    /**
     * Applies the displacement in the target's current orientation and scale.
     * Use on the scene thread, on a non-physics actor or wrapper.
     *
     * @param target spatial to move and turn
     */
    public void applyTo(Spatial target) {
        if (x != 0 || z != 0) {
            target.move(target.getLocalRotation().mult(getTranslation().multLocal(target.getLocalScale())));
        }
        if (yaw != 0) {
            target.rotate(new Quaternion().fromAngleNormalAxis((float) yaw, Vector3f.UNIT_Y));
        }
    }

    RootMotionDelta then(RootMotionDelta next) {
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        return new RootMotionDelta(x + c * next.x + s * next.z,
                z - s * next.x + c * next.z, yaw + next.yaw);
    }

    RootMotionDelta inverse() {
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        return new RootMotionDelta(-c * x + s * z, -s * x - c * z, -yaw);
    }

    RootMotionDelta repeated(long count) {
        RootMotionDelta result = ZERO;
        RootMotionDelta power = this;
        for (long remaining = count; remaining > 0; remaining >>>= 1) {
            if ((remaining & 1L) != 0) {
                result = result.then(power);
            }
            if (remaining > 1) {
                power = power.then(power);
            }
        }
        return result;
    }

    RootMotionDelta plusWeighted(RootMotionDelta delta, double amount) {
        return new RootMotionDelta(x + amount * delta.x, z + amount * delta.z, yaw + amount * delta.yaw);
    }
}

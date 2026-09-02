package com.sHDFGamePlugin.infrastructure.regionNotation;

import org.bukkit.util.Vector;

/**
 * 球体区域：由球心（center）和半径（radius）定义。
 * <p>
 * 当前暂未被使用（炸弹操作范围现用 {@link CubeRegion}），保留供后续玩法使用。
 */
public class SphereRegion implements Region {

    private final Vector center;
    private final double radius;

    private SphereRegion(Vector center, double radius) {
        if (center == null) {
            throw new IllegalArgumentException("Center cannot be null!");
        }
        if (radius <= 0 || Double.isNaN(radius) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("Radius must be positive and finite!");
        }
        this.center = center.clone();
        this.radius = radius;
    }

    public Vector getCenter() {
        return center.clone();
    }

    public double getRadius() {
        return radius;
    }

    @Override
    public boolean contains(Vector point) {
        return point != null && point.distanceSquared(center) <= radius * radius;
    }

    @Override
    public SphereRegion copy() {
        return new SphereRegion(center, radius);
    }

    //创建实例的静态方法
    public static SphereRegion create(Vector center, double radius) {
        return new SphereRegion(center, radius);
    }
}

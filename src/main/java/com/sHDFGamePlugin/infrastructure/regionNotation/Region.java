package com.sHDFGamePlugin.infrastructure.regionNotation;

import org.bukkit.util.Vector;

/**
 * 区域抽象：由具体实现提供"是否包含某点"与"中心点"的判定。
 * <p>
 * 实现：{@link CubeRegion}（立方体）、{@link SphereRegion}（球体）。
 */
public interface Region{

    public Vector getCenter();

    public boolean contains(Vector point);

    public Region copy();

    public Vector randomPoint();

}

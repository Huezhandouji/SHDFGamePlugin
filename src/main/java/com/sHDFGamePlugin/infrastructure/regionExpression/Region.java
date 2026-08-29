package com.sHDFGamePlugin.infrastructure.regionExpression;

import org.bukkit.util.Vector;

public interface Region{

    public Vector getCenter();

    public boolean contains(Vector point);

    public Region copy();

}

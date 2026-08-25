package com.sHDFGamePlugin.domain.sector;

import org.bukkit.util.Vector;

public class Region{
    private final Vector origin;
    private final Vector size;

    //必须保证size的分量都是正数
    private Region(Vector origin, Vector size){
        if(size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0){
            throw new IllegalArgumentException("All vector components of 'size' must be positive!");
        }
        if (Double.isNaN(size.getX()) || Double.isInfinite(size.getX()) ||
                Double.isNaN(size.getY()) || Double.isInfinite(size.getY()) ||
                Double.isNaN(size.getZ()) || Double.isInfinite(size.getZ())) {
            throw new IllegalArgumentException("All components of 'size' cannot be NaN or Infinite!");
        }
        this.origin = origin.clone();
        this.size = size.clone();
    }

    public Vector getOrigin() {
        return origin.clone();
    }
    public Vector getSize() {
        return size.clone();
    }

    public Vector getCenter() {
        return new Vector(
                origin.getX() + size.getX() / 2d,
                origin.getY() + size.getY() / 2d,
                origin.getZ() + size.getZ() / 2d
        );
    }

    public boolean contains(Vector vector){
        return vector.getX() >= origin.getX() && vector.getX() <= origin.getX() + size.getX()
                && vector.getY() >= origin.getY() && vector.getY() <= origin.getY() + size.getY()
                && vector.getZ() >= origin.getZ() && vector.getZ() <= origin.getZ() + size.getZ();
    }

    public Region copy(){
        return new Region(origin, size);
    }

    public Vector randomPoint(){
        double x = origin.getX() + Math.random() * size.getX();
        double y = origin.getY() + Math.random() * size.getY();
        double z = origin.getZ() + Math.random() * size.getZ();
        return new Vector(x, y, z);
    }




    //创建实例的静态方法
    public static Region createFromOriginAndSize(Vector origin, Vector size){
        return new Region(origin, size);
    }

    public static Region createFromCorners(Vector a, Vector b){
        double minX = Math.min(a.getX(), b.getX());
        double minY = Math.min(a.getY(), b.getY());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxX = Math.max(a.getX(), b.getX());
        double maxY = Math.max(a.getY(), b.getY());
        double maxZ = Math.max(a.getZ(), b.getZ());
        Vector origin = new Vector(minX, minY, minZ);
        Vector size = new Vector(maxX -  minX, maxY - minY, maxZ - minZ);
        return new Region(origin, size);
    }



}

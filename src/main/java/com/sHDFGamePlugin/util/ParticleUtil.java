package com.sHDFGamePlugin.util;

import com.sHDFGamePlugin.domain.sector.Region;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

public class ParticleUtil {

    //用粒子绘制Region的8条边
    public static void drawRegionEdges(Region region, World world, Particle particle, double step){
        Vector origin = region.getOrigin();
        Vector size = region.getSize();

        double minX = origin.getX();
        double minY = origin.getY();
        double minZ = origin.getZ();
        double maxX = origin.getX() + size.getX();
        double maxY = origin.getY() + size.getY();
        double maxZ = origin.getZ() + size.getZ();

        Vector[] bottomCorners = {
                new Vector(minX, minY, minZ),
                new Vector(maxX, minY, minZ),
                new Vector(maxX, minY, maxZ),
                new Vector(minX, minY, maxZ),
        };

        Vector[] topCorners = {
                new Vector(maxX, maxY, minZ),
                new Vector(maxX, maxY, minZ),
                new Vector(maxX, maxY, maxZ),
                new Vector(minX, maxY, maxZ),
        };

        //四个垂直边
        for(int i = 0; i < 4; i++){
            drawLine(world, bottomCorners[i], topCorners[i], particle, step);
        }

        //底面四个水平边
        for(int i = 0; i < 4; i++){
            drawLine(world, bottomCorners[i], bottomCorners[(i + 1) % 4], particle, step);
        }

        //顶面四个水平边
        for(int i = 0; i < 4; i++){
            drawLine(world, topCorners[i], topCorners[(i + 1) % 4], particle, step);
        }
    }


    public static void drawLine(World world, Vector start, Vector end, Particle particle, double step){
        double distance = start.distance(end);
        int points = Math.max(1, (int)(distance / step));

        for(int i = 0; i <= points; i++){
            double t = (double) i / points;
            double x = start.getX() + (end.getX() - start.getX()) * t;
            double y = start.getY() + (end.getY() - start.getY()) * t;
            double z = start.getZ() + (end.getZ() - start.getZ()) * t;
            world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
        }
    }

}

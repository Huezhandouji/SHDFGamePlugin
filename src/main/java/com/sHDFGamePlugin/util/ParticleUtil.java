package com.sHDFGamePlugin.util;

import com.sHDFGamePlugin.infrastructure.regionNotation.CubeRegion;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * 粒子工具：用粒子可视化绘制区域边界（表现/调试用）。
 * <p>
 * 提供带/不带 {@code data} 两套重载：{@link Particle#DUST} 等需要 {@code Particle.DustOptions}
 * 的粒子走 {@link #drawRegionEdges(CubeRegion, World, Particle, double, Object)}。
 */
public class ParticleUtil {

    //用粒子绘制Region的8条边
    public static void drawRegionEdges(CubeRegion region, World world, Particle particle, double step){
        drawRegionEdges(region, world, particle, step, null);
    }

    /**
     * 用粒子绘制 Region 的 8 条边（带粒子 data 版本，供 {@link Particle#DUST} 等使用）。
     *
     * @param data 粒子附加数据（如 {@code new Particle.DustOptions(Color, size)}）；无需数据时传 {@code null}
     */
    public static void drawRegionEdges(CubeRegion region, World world, Particle particle, double step, Object data){
        if(region == null || world == null || particle == null || step <= 0){
            return;
        }
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

        //注意：topCorners[0] 必须是 (minX, maxY, minZ)——它与 bottomCorners[0] 配对构成竖边；
        //历史缺陷：曾误写成 (maxX, maxY, minZ)（与 [1] 重复），导致第 1 条竖边变成对角线、顶面一条边退化。
        Vector[] topCorners = {
                new Vector(minX, maxY, minZ),
                new Vector(maxX, maxY, minZ),
                new Vector(maxX, maxY, maxZ),
                new Vector(minX, maxY, maxZ),
        };

        //四个垂直边
        for(int i = 0; i < 4; i++){
            drawLine(world, bottomCorners[i], topCorners[i], particle, step, data);
        }

        //底面四个水平边
        for(int i = 0; i < 4; i++){
            drawLine(world, bottomCorners[i], bottomCorners[(i + 1) % 4], particle, step, data);
        }

        //顶面四个水平边
        for(int i = 0; i < 4; i++){
            drawLine(world, topCorners[i], topCorners[(i + 1) % 4], particle, step, data);
        }
    }


    public static void drawLine(World world, Vector start, Vector end, Particle particle, double step){
        drawLine(world, start, end, particle, step, null);
    }

    /** 画一条线（带粒子 data 版本） */
    public static void drawLine(World world, Vector start, Vector end, Particle particle, double step, Object data){
        if(world == null || start == null || end == null || particle == null || step <= 0){
            return;
        }
        double distance = start.distance(end);
        int points = Math.max(1, (int)(distance / step));

        for(int i = 0; i <= points; i++){
            double t = (double) i / points;
            double x = start.getX() + (end.getX() - start.getX()) * t;
            double y = start.getY() + (end.getY() - start.getY()) * t;
            double z = start.getZ() + (end.getZ() - start.getZ()) * t;
            if(data == null){
                world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
            }
            else{
                world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, data);
            }
        }
    }

}

package com.sHDFGamePlugin.infrastructure.config;

import com.sHDFGamePlugin.infrastructure.regionExpression.CubeRegion;
import net.kyori.adventure.text.Component;

/**
 * 炸弹静态配置（对应 maps.yml 中 objective 下的 bombs 列表项）。
 * <p>
 * 加载后不可变；运行时状态由 domain 层的 ActiveBomb 维护。
 */
public class BombConfig {

    private final String id;             // 炸弹唯一标识（据点内唯一）
    private final Component name;        // 炸弹显示名
    private final CubeRegion region;     // 炸弹操作范围（安放/拆除距离判定）
    private final int plantTime;         // 安放炸弹所需时间（tick）
    private final int fuseTime;          // 引信倒计时（tick）
    private final int defuseTime;        // 拆除炸弹所需时间（tick）

    private BombConfig(String id, Component name, CubeRegion region,
                       int plantTime, int fuseTime, int defuseTime) {
        this.id = id;
        this.name = name;
        this.region = region;
        this.plantTime = plantTime;
        this.fuseTime = fuseTime;
        this.defuseTime = defuseTime;
    }

    public static class Builder {
        private String id;
        private Component name;
        private CubeRegion region;
        private int plantTime;
        private int fuseTime;
        private int defuseTime;

        private Builder() {}

        public static Builder create() {
            return new Builder();
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(Component name) {
            this.name = name;
            return this;
        }

        public Builder region(CubeRegion region) {
            this.region = region;
            return this;
        }

        public Builder plantTime(int plantTime) {
            this.plantTime = plantTime;
            return this;
        }

        public Builder fuseTime(int fuseTime) {
            this.fuseTime = fuseTime;
            return this;
        }

        public Builder defuseTime(int defuseTime) {
            this.defuseTime = defuseTime;
            return this;
        }

        public BombConfig build() {
            if (id == null || id.isEmpty()) {
                throw new IllegalStateException("Field: id is required!");
            }
            if (name == null) {
                throw new IllegalStateException("Field: name is required!");
            }
            if (region == null) {
                throw new IllegalStateException("Field: region (bomb operation range) is required!");
            }
            if (plantTime <= 0 || fuseTime <= 0 || defuseTime <= 0) {
                throw new IllegalStateException("Bomb times must be positive!");
            }
            return new BombConfig(id, name, region, plantTime, fuseTime, defuseTime);
        }
    }

    public String getId() {
        return id;
    }

    public Component getName() {
        return name;
    }

    public CubeRegion getRegion() {
        return region;
    }

    public int getPlantTime() {
        return plantTime;
    }

    public int getFuseTime() {
        return fuseTime;
    }

    public int getDefuseTime() {
        return defuseTime;
    }
}

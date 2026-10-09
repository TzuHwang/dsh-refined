package com.jasoncian.millenaire_rewrite.entity.villager;

import org.jetbrains.annotations.Nullable;

/**
 * 村民模型类型，对应原版村民配置中的 model= 字段。
 * 原版皮肤按各自模型的 UV 布局绘制，必须用对应模型渲染。
 */
public enum VillagerModelType {
    /** 标准 64x32 人形 */
    DEFAULT("default"),
    /** 女性：窄身体、细手臂、胸部，左腿使用独立贴图区域 */
    FEMALE_ASYMMETRICAL("femaleasymmetrical"),
    /** 女性：同上，但左右腿共用贴图 */
    FEMALE_SYMMETRICAL("femalesymmetrical");

    private final String id;

    VillagerModelType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static VillagerModelType fromId(@Nullable String id) {
        for (VillagerModelType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return DEFAULT;
    }
}

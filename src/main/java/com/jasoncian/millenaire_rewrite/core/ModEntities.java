package com.jasoncian.millenaire_rewrite.core;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 模组实体注册器
 *
 * 负责注册所有Millenaire mod的实体类型
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES, MillenaireRewrite.MOD_ID);

    // ================ 村民实体 ================

    /** Millenaire村民 - 主要村民实体 */
    public static final RegistryObject<EntityType<MillVillager>> MILL_VILLAGER =
        ENTITY_TYPES.register("mill_villager", () -> EntityType.Builder
            .of(MillVillager::new, MobCategory.MISC)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(10)
            .build(MillenaireRewrite.MOD_ID + ":mill_villager"));

    /**
     * 注册所有实体类型到模组事件总线
     *
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }
}

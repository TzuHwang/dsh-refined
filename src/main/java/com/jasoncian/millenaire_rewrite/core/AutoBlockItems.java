package com.jasoncian.millenaire_rewrite.core;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 为本模组所有还没有物品的方块注册方块物品（建筑方块族：泥砖、木框架、路面等）。
 * 村庄交易和建造都以物品计算材料，没有物品的方块无法买卖、也无法当建材搬运。
 * 在其他物品注册之后运行（LOWEST），已有同名物品的方块跳过。
 */
@Mod.EventBusSubscriber(modid = MillenaireRewrite.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AutoBlockItems {

    /** 自动注册了物品的方块（数据生成用来补物品模型） */
    public static final List<ResourceLocation> REGISTERED = new ArrayList<>();

    private AutoBlockItems() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(ForgeRegistries.Keys.ITEMS)) {
            return;
        }
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                // 作物方块不给物品（玩家不应直接放置作物）
                if (!id.getNamespace().equals(MillenaireRewrite.MOD_ID) || ForgeRegistries.ITEMS.containsKey(id)
                    || block.asItem() != net.minecraft.world.item.Items.AIR
                    || block instanceof net.minecraft.world.level.block.CropBlock) {
                    continue;
                }
                helper.register(id, new BlockItem(block, new Item.Properties()));
                REGISTERED.add(id);
            }
            MillenaireRewrite.LOGGER.info("Registered {} block items for building blocks", REGISTERED.size());
        });
    }
}

package com.jasoncian.millenaire_rewrite.datagen;

import com.jasoncian.millenaire_rewrite.blocks.base.BaseBuildingSlabBlock;
import com.jasoncian.millenaire_rewrite.blocks.decorative.PathBlock;
import com.jasoncian.millenaire_rewrite.blocks.system.BuildingBlockRegistry;
import com.jasoncian.millenaire_rewrite.blocks.agriculture.MillCropBlock;
import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import com.jasoncian.millenaire_rewrite.core.ModItems;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;

/**
 * 方块战利品表数据生成器
 * 
 * 负责生成方块被破坏时掉落的物品
 * Village Stone根据legacy逻辑不应该掉落任何物品
 * 
 * ⚠️  注意：装饰方块战利品表已弃用，需要为新的统一方块系统添加支持
 */
public class ModBlockLootTables extends BlockLootSubProvider {

    public ModBlockLootTables() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        // Village Stone - 不掉落任何物品（参考legacy的quantityDropped返回0）
        // 通过不添加战利品表来实现无掉落

        // ================ BuildingBlockRegistry 自动战利品表生成 ================
        // 自动为所有建筑方块生成战利品表
        for (var entry : BuildingBlockRegistry.getAllBlocks().entrySet()) {
            Block block = entry.getValue().get();

            if (block instanceof SlabBlock slabBlock) {
                // 台阶方块使用特殊掉落逻辑（放置两个时掉落1个）
                this.add(slabBlock, createSlabItemTable(slabBlock));
            } else {
                // 其他方块掉落自身
                this.dropSelf(block);
            }
        }

        // ================ ModBlocks 战利品表 ================
        // 有方块物品的掉落自身，其余（市政厅、树叶、蚕等）不掉落
        for (RegistryObject<Block> holder : ModBlocks.BLOCKS.getEntries()) {
            Block block = holder.get();
            if (block.asItem() != Items.AIR) {
                this.dropSelf(block);
            } else {
                this.add(block, noDrop());
            }
        }

        // 作物：成熟时掉落作物，否则掉落一个作为种子
        cropDrops(ModBlocks.CROP_RICE.get(), ModItems.RICE.get());
        cropDrops(ModBlocks.CROP_TURMERIC.get(), ModItems.TURMERIC.get());
        cropDrops(ModBlocks.CROP_MAIZE.get(), ModItems.MAIZE.get());
        cropDrops(ModBlocks.CROP_COTTON.get(), ModItems.COTTON.get());
        this.add(ModBlocks.CROP_VINE.get(), createSingleItemTable(ModItems.GRAPES.get()));

        // 矿石：掉落矿物（受时运影响）
        this.add(ModBlocks.GALIANITE_ORE.get(),
            block -> createOreDrop(block, ModItems.GALIANITE_ORE_ITEM.get()));
        this.add(ModBlocks.DEEPSLATE_GALIANITE_ORE.get(),
            block -> createOreDrop(block, ModItems.GALIANITE_ORE_ITEM.get()));
    }

    private void cropDrops(Block crop, Item item) {
        this.add(crop, createCropDrops(crop, item, item,
            LootItemBlockStatePropertyCondition.hasBlockStateProperties(crop)
                .setProperties(StatePropertiesPredicate.Builder.properties()
                    .hasProperty(MillCropBlock.AGE, MillCropBlock.MAX_AGE))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        // 合并 ModBlocks 和 BuildingBlockRegistry 中的所有方块
        java.util.List<Block> allBlocks = new java.util.ArrayList<>();

        // 添加 ModBlocks 中的方块
        ModBlocks.BLOCKS.getEntries().stream()
            .map(holder -> (Block) holder.get())
            .forEach(allBlocks::add);

        // 添加 BuildingBlockRegistry 中的方块
        BuildingBlockRegistry.getAllBlocks().values().stream()
            .map(holder -> (Block) holder.get())
            .forEach(allBlocks::add);

        return allBlocks;
    }
}

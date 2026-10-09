package com.jasoncian.millenaire_rewrite.datagen;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.blocks.agriculture.FruitLeavesBlock;
import com.jasoncian.millenaire_rewrite.blocks.agriculture.GrapeVineBlock;
import com.jasoncian.millenaire_rewrite.blocks.agriculture.MillCropBlock;
import com.jasoncian.millenaire_rewrite.blocks.agriculture.SilkWormBlock;
import com.jasoncian.millenaire_rewrite.blocks.base.BaseBuildingBlock;
import com.jasoncian.millenaire_rewrite.blocks.base.BaseBuildingSlabBlock;
import com.jasoncian.millenaire_rewrite.blocks.base.BaseBuildingStairsBlock;
import com.jasoncian.millenaire_rewrite.blocks.base.BaseBuildingWallBlock;
import com.jasoncian.millenaire_rewrite.blocks.decorative.PathBlock;
import com.jasoncian.millenaire_rewrite.blocks.functional.FirePitBlock;
import com.jasoncian.millenaire_rewrite.blocks.system.BasicBuildingMaterial;
import com.jasoncian.millenaire_rewrite.blocks.system.BlockVariantType;
import com.jasoncian.millenaire_rewrite.blocks.system.BuildingBlockRegistry;
import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ConfiguredModel;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;

/**
 * 方块状态数据生成器
 *
 * 为统一建筑方块系统和 ModBlocks 中的功能/农业方块生成 blockstates 和模型。
 * 材质优先使用本模组材质，缺失时退回到外观相近的原版材质，
 * 保证所有方块都能正常显示（之后可以逐步替换为专用材质）。
 */
public class ModBlockStateProvider extends BlockStateProvider {

    /**
     * 建筑材料 -> 材质。"minecraft:" 前缀表示原版材质，其余为本模组 block/ 目录下的材质。
     */
    private static final Map<BasicBuildingMaterial, String> MATERIAL_TEXTURES = Map.ofEntries(
        Map.entry(BasicBuildingMaterial.STONE, "minecraft:stone"),
        Map.entry(BasicBuildingMaterial.COBBLESTONE, "minecraft:cobblestone"),
        Map.entry(BasicBuildingMaterial.STONE_BRICKS, "minecraft:stone_bricks"),
        Map.entry(BasicBuildingMaterial.MOSSY_STONE_BRICKS, "minecraft:mossy_stone_bricks"),
        Map.entry(BasicBuildingMaterial.CRACKED_STONE_BRICKS, "minecraft:cracked_stone_bricks"),
        Map.entry(BasicBuildingMaterial.CHISELED_STONE_BRICKS, "minecraft:chiseled_stone_bricks"),
        Map.entry(BasicBuildingMaterial.SANDSTONE, "minecraft:sandstone"),
        Map.entry(BasicBuildingMaterial.CHISELED_SANDSTONE, "minecraft:chiseled_sandstone"),
        Map.entry(BasicBuildingMaterial.CUT_SANDSTONE, "minecraft:cut_sandstone"),
        Map.entry(BasicBuildingMaterial.SMOOTH_SANDSTONE, "minecraft:sandstone_top"),
        Map.entry(BasicBuildingMaterial.RED_SANDSTONE, "minecraft:red_sandstone"),
        Map.entry(BasicBuildingMaterial.CHISELED_RED_SANDSTONE, "minecraft:chiseled_red_sandstone"),
        Map.entry(BasicBuildingMaterial.CUT_RED_SANDSTONE, "minecraft:cut_red_sandstone"),
        Map.entry(BasicBuildingMaterial.SMOOTH_RED_SANDSTONE, "minecraft:red_sandstone_top"),
        Map.entry(BasicBuildingMaterial.MUD_BRICKS, "mudbrick"),
        Map.entry(BasicBuildingMaterial.DRIED_BRICKS, "dried_brick"),
        Map.entry(BasicBuildingMaterial.COOKED_BRICKS, "cooked_brick"),
        Map.entry(BasicBuildingMaterial.GOLD_ORNAMENT, "gold_ornament"),
        Map.entry(BasicBuildingMaterial.GALIANITE_BLOCK, "galianite_block"),
        Map.entry(BasicBuildingMaterial.SIMPLE_TIMBER_FRAME, "plain_timber_frame"),
        Map.entry(BasicBuildingMaterial.CROSS_TIMBER_FRAME, "cross_timber_frame"),
        Map.entry(BasicBuildingMaterial.EARTH_WALL, "dirt_wall"),
        Map.entry(BasicBuildingMaterial.BYZANTINE_TILES, "byzantine_tiles_top"),
        Map.entry(BasicBuildingMaterial.GRAY_TILES, "gray_tiles_0"),
        Map.entry(BasicBuildingMaterial.GREEN_TILES, "green_tiles_0"),
        Map.entry(BasicBuildingMaterial.RED_TILES, "red_tiles_0"),
        Map.entry(BasicBuildingMaterial.OCHRE_TILES, "pathochretiles"),
        Map.entry(BasicBuildingMaterial.TIMBER_FRAME, "plain_timber_frame"),
        Map.entry(BasicBuildingMaterial.THATCH, "thatch"),
        Map.entry(BasicBuildingMaterial.ICE_BRICKS, "icebrick"),
        Map.entry(BasicBuildingMaterial.SNOW_BRICKS, "snowbrick"),
        Map.entry(BasicBuildingMaterial.BYZANTINE_MOSAIC_RED, "byzantine_mosaic_red"),
        Map.entry(BasicBuildingMaterial.BYZANTINE_MOSAIC_BLUE, "byzantine_mosaic_blue"),
        Map.entry(BasicBuildingMaterial.LIGHT_BLUE_BRICK, "lightbluebrick_block"),
        Map.entry(BasicBuildingMaterial.CHISELED_LIGHT_BLUE_BRICK, "lightbluechiseled_block"),
        Map.entry(BasicBuildingMaterial.MAYAN_GOLD_BLOCK, "minecraft:gold_block"),
        Map.entry(BasicBuildingMaterial.HONEY_BLOCK, "minecraft:honeycomb_block"),
        Map.entry(BasicBuildingMaterial.STONE_PATH, "pathslabs"),
        Map.entry(BasicBuildingMaterial.SANDSTONE_PATH, "pathsandstone"),
        Map.entry(BasicBuildingMaterial.GRAVEL_PATH, "pathgravel"),
        Map.entry(BasicBuildingMaterial.WOODEN_PATH, "planks_oak")
    );

    private static final ExistingFileHelper.ResourceType TEXTURE =
        new ExistingFileHelper.ResourceType(PackType.CLIENT_RESOURCES, ".png", "textures");

    private final ExistingFileHelper existingFileHelper;

    public ModBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, MillenaireRewrite.MOD_ID, exFileHelper);
        this.existingFileHelper = exFileHelper;
    }

    @Override
    protected void registerStatesAndModels() {
        MillenaireRewrite.LOGGER.info("Generating block states and models...");

        int blockCount = 0;
        for (var entry : BuildingBlockRegistry.getAllBlocks().entrySet()) {
            String registryName = entry.getKey();
            Block block = entry.getValue().get();

            if (block instanceof PathBlock path) {
                pathBlock(registryName, block, path.getBaseMaterial());
            } else if (block instanceof BaseBuildingStairsBlock stairs) {
                stairsBlock(stairs, materialTexture(stairs.getBaseMaterial()));
            } else if (block instanceof BaseBuildingSlabBlock slab) {
                ResourceLocation texture = materialTexture(slab.getBaseMaterial());
                ModelFile doubleSlab = models().cubeAll(
                    slab.getBaseMaterial().generateBlockRegistryName(slab.getCulture(), BlockVariantType.BLOCK),
                    texture);
                slabBlock(slab,
                    models().slab(registryName, texture, texture, texture),
                    models().slabTop(registryName + "_top", texture, texture, texture),
                    doubleSlab);
            } else if (block instanceof BaseBuildingWallBlock wall) {
                wallBlock(wall, materialTexture(wall.getBaseMaterial()));
            } else if (block instanceof BaseBuildingBlock base) {
                simpleBlock(block, models().cubeAll(registryName, materialTexture(base.getBaseMaterial())));
            } else {
                continue;
            }
            blockCount++;
        }

        registerModBlocks();

        MillenaireRewrite.LOGGER.info("Generated block states for {} building blocks", blockCount);
    }

    // ================ ModBlocks ================

    private void registerModBlocks() {
        // 简单建筑方块
        cubeWithItem(ModBlocks.MUD_BRICK_BLOCK, modTex("mudbrick"));
        cubeWithItem(ModBlocks.COOKED_BRICK_BLOCK, modTex("cooked_brick"));
        cubeWithItem(ModBlocks.THATCH_BLOCK, modTex("thatch"));
        cubeWithItem(ModBlocks.PAPER_WALL_BLOCK, modTex("paper_wall"));
        cubeWithItem(ModBlocks.TIMBER_FRAME_BLOCK, modTex("plain_timber_frame"));
        cubeWithItem(ModBlocks.WATTLE_DAUB_BLOCK, modTex("dirt_wall"));
        cubeWithItem(ModBlocks.PLASTER_BLOCK, modTex("mudbrick_smooth"));
        cubeWithItem(ModBlocks.BYZANTINE_TILE_BLOCK, modTex("byzantine_tiles_top"));
        cubeWithItem(ModBlocks.MARBLE_BLOCK, mcLoc("block/calcite"));
        cubeWithItem(ModBlocks.LIMESTONE_BLOCK, mcLoc("block/smooth_stone"));
        cubeWithItem(ModBlocks.GLAZED_TILE_BLOCK, modTex("green_tiles_0"));
        cubeWithItem(ModBlocks.CARVED_STONE_BLOCK, modTex("sandstone_carved"));
        // 占位：Galianite 矿石暂用青金石矿外观
        cubeWithItem(ModBlocks.GALIANITE_ORE, mcLoc("block/lapis_ore"));
        cubeWithItem(ModBlocks.DEEPSLATE_GALIANITE_ORE, mcLoc("block/deepslate_lapis_ore"));

        // 市政厅（村庄核心方块）
        ModelFile townHall = models().cubeAll("town_hall", modTex("village_stone"));
        horizontalBlock(ModBlocks.TOWN_HALL_NORMAN.get(), townHall);
        horizontalBlock(ModBlocks.TOWN_HALL_JAPANESE.get(), townHall);
        horizontalBlock(ModBlocks.TOWN_HALL_INDIAN.get(), townHall);
        horizontalBlock(ModBlocks.TOWN_HALL_MAYAN.get(), townHall);
        horizontalBlock(ModBlocks.TOWN_HALL_BYZANTINE.get(), townHall);

        // 导入桌
        ModelFile importTable = models().cube("import_table",
                modTex("planks_oak"), modTex("crafting_table_top"),
                modTex("crafting_table_front"), modTex("crafting_table_side"),
                modTex("crafting_table_side"), modTex("crafting_table_front"))
            .texture("particle", modTex("crafting_table_front"));
        simpleBlockWithItem(ModBlocks.IMPORT_TABLE.get(), importTable);

        // 火坑：沿用原版营火模型
        ModelFile firePitLit = models().getExistingFile(mcLoc("block/campfire"));
        ModelFile firePitOff = models().getExistingFile(mcLoc("block/campfire_off"));
        getVariantBuilder(ModBlocks.FIRE_PIT.get()).forAllStates(state -> ConfiguredModel.builder()
            .modelFile(state.getValue(FirePitBlock.LIT) ? firePitLit : firePitOff)
            .rotationY((int) state.getValue(FirePitBlock.FACING).toYRot())
            .build());
        itemModels().withExistingParent("fire_pit", mcLoc("item/campfire"));

        // 上锁箱子：由方块实体渲染，模型只提供破坏粒子
        ModelFile chestParticle = models().getBuilder("locked_chest")
            .texture("particle", mcLoc("block/oak_planks"));
        getVariantBuilder(ModBlocks.LOCKED_CHEST.get()).forAllStates(state ->
            ConfiguredModel.builder().modelFile(chestParticle).build());
        itemModels().withExistingParent("locked_chest", mcLoc("item/chest"));

        // 作物：前半段生长使用 0 号材质，后半段使用 1 号材质
        cropBlock(ModBlocks.CROP_RICE, "rice");
        cropBlock(ModBlocks.CROP_TURMERIC, "turmeric");
        cropBlock(ModBlocks.CROP_MAIZE, "maize");
        cropBlock(ModBlocks.CROP_COTTON, "cotton");

        // 葡萄藤（双格高）
        getVariantBuilder(ModBlocks.CROP_VINE.get()).forAllStates(state -> {
            int stage = state.getValue(GrapeVineBlock.AGE) < 4 ? 0 : 1;
            String part = state.getValue(GrapeVineBlock.HALF) == DoubleBlockHalf.LOWER ? "lower" : "upper";
            String name = "vine" + stage + part;
            return ConfiguredModel.builder()
                .modelFile(models().cross("crop_vine_" + stage + "_" + part, modTex(name)).renderType("cutout"))
                .build();
        });

        // 蚕
        getVariantBuilder(ModBlocks.SILKWORM.get()).forAllStates(state -> {
            String texture = switch (state.getValue(SilkWormBlock.PROGRESS)) {
                case EMPTY -> "silkwormempty";
                case PROGRESS_1 -> "silkwormip1";
                case PROGRESS_2 -> "silkwormip2";
                case FULL -> "silkwormfull";
            };
            return ConfiguredModel.builder()
                .modelFile(models().cubeAll("silkworm_" + texture, modTex(texture)))
                .build();
        });

        // 果树叶：年龄 0~3 依次为 叶子/开花/未熟果/成熟果
        fruitLeaves(ModBlocks.LEAVES_APPLETREE, new String[] {
            "leaves_appletree_leaves", "leaves_appletree_blossom",
            "leaves_appletree_unripefruit", "leaves_appletree_fruit"});
        fruitLeaves(ModBlocks.LEAVES_OLIVETREE, new String[] {
            "leaves_olivetree_leaves", "leaves_olivetree_blossom",
            "leaves_olivetree_unripefruit", "leaves_olivetree_fruit"});
        // 占位：开心果树暂用橄榄树材质
        fruitLeaves(ModBlocks.LEAVES_PISTACHIO, new String[] {
            "leaves_olivetree_leaves", "leaves_olivetree_blossom",
            "leaves_olivetree_unripefruit", "leaves_olivetree_fruit"});
        String[] cherry = {"cherry_leaves_0", "cherry_leaves_1", "cherry_leaves_2", "cherry_leaves_3"};
        fruitLeaves(ModBlocks.CHERRY_LEAVES, cherry);
        fruitLeaves(ModBlocks.SAKURA_LEAVES, cherry);
    }

    private void cubeWithItem(RegistryObject<? extends Block> block, ResourceLocation texture) {
        simpleBlockWithItem(block.get(), models().cubeAll(block.getId().getPath(), texture));
    }

    private void cropBlock(RegistryObject<MillCropBlock> crop, String textureBase) {
        String name = crop.getId().getPath();
        getVariantBuilder(crop.get()).forAllStates(state -> {
            int stage = state.getValue(MillCropBlock.AGE) < 4 ? 0 : 1;
            return ConfiguredModel.builder()
                .modelFile(models().crop(name + "_stage" + stage, modTex(textureBase + stage)).renderType("cutout"))
                .build();
        });
    }

    private void fruitLeaves(RegistryObject<FruitLeavesBlock> leaves, String[] texturesByAge) {
        String name = leaves.getId().getPath();
        getVariantBuilder(leaves.get()).forAllStatesExcept(state -> {
            int age = state.getValue(FruitLeavesBlock.AGE);
            return ConfiguredModel.builder()
                .modelFile(models().withExistingParent(name + "_age" + age, mcLoc("block/leaves"))
                    .texture("all", modTex(texturesByAge[age]))
                    .renderType("cutout_mipped"))
                .build();
        }, BlockStateProperties.DISTANCE, BlockStateProperties.PERSISTENT, BlockStateProperties.WATERLOGGED);
    }

    // ================ 建筑方块 ================

    private void pathBlock(String registryName, Block block, BasicBuildingMaterial material) {
        ResourceLocation top = materialTexture(material);
        ResourceLocation side = new ResourceLocation(top.getNamespace(), top.getPath() + "_side");
        if (!textureExists(side)) {
            side = top;
        }
        ModelFile model = models().withExistingParent(registryName, mcLoc("block/dirt_path"))
            .texture("top", top)
            .texture("side", side)
            .texture("bottom", top)
            .texture("particle", top);
        simpleBlock(block, model);
    }

    private ResourceLocation materialTexture(BasicBuildingMaterial material) {
        String texture = MATERIAL_TEXTURES.get(material);
        if (texture == null) {
            throw new IllegalStateException("No texture mapped for building material " + material);
        }
        if (texture.startsWith("minecraft:")) {
            return mcLoc("block/" + texture.substring("minecraft:".length()));
        }
        return modTex(texture);
    }

    private ResourceLocation modTex(String name) {
        return modLoc("block/" + name);
    }

    private boolean textureExists(ResourceLocation texture) {
        return existingFileHelper.exists(texture, TEXTURE);
    }
}

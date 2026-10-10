package com.jasoncian.millenaire_rewrite.content;

import com.jasoncian.millenaire_rewrite.core.ModBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/**
 * 图纸耕作点类型、原版作物类型（goals 中的 croptype）与 1.20.1 作物方块的对应。
 */
public final class Crops {

    private Crops() {
    }

    /**
     * 耕作点上种的作物；没有对应作物（葡萄藤、甘蔗、花、可可）时返回 null
     */
    @Nullable
    public static Block cropForSoil(String soilType) {
        return switch (soilType) {
            case "soil" -> Blocks.WHEAT;
            case "carrotsoil" -> Blocks.CARROTS;
            case "potatosoil" -> Blocks.POTATOES;
            case "ricesoil" -> ModBlocks.CROP_RICE.get();
            case "turmericsoil" -> ModBlocks.CROP_TURMERIC.get();
            case "maizesoil" -> ModBlocks.CROP_MAIZE.get();
            case "cottonsoil" -> ModBlocks.CROP_COTTON.get();
            default -> null;
        };
    }

    /**
     * 原版 croptype（1.12 方块名）对应的耕作点类型
     */
    @Nullable
    public static String soilForCropType(@Nullable String cropType) {
        if (cropType == null) {
            return null;
        }
        return switch (cropType) {
            case "wheat" -> "soil";
            case "carrots" -> "carrotsoil";
            case "potatoes" -> "potatosoil";
            case "millenaire:crop_rice" -> "ricesoil";
            case "millenaire:crop_turmeric" -> "turmericsoil";
            case "millenaire:crop_maize" -> "maizesoil";
            case "millenaire:crop_cotton" -> "cottonsoil";
            default -> null;
        };
    }

    /**
     * 原版资源方块（goals 中的 sourceblockstate）对应的资源点类型
     */
    @Nullable
    public static String sourcePointFor(@Nullable String sourceBlock) {
        if (sourceBlock == null) {
            return null;
        }
        return switch (sourceBlock) {
            case "minecraft:stone" -> "stonesource";
            case "minecraft:sand" -> "sandsource";
            case "minecraft:gravel" -> "gravelsource";
            case "minecraft:clay" -> "claysource";
            case "minecraft:sandstone" -> "sandstonesource";
            case "minecraft:red_sandstone" -> "redsandstonesource";
            case "minecraft:ice" -> "icesource";
            case "minecraft:snow_layer" -> "snowsource";
            default -> null;
        };
    }
}

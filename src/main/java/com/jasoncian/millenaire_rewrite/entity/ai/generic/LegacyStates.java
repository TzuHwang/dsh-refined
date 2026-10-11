package com.jasoncian.millenaire_rewrite.entity.ai.generic;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;

/**
 * 原版目标文件里的 1.12 方块状态（如 red_flower;type=blue_orchid）-> 1.20.1 方块状态。
 * 目标文件只用到少数几种，直接对照。
 */
public final class LegacyStates {

    private static final Map<String, String> MAP = Map.ofEntries(
        Map.entry("red_flower;type=poppy", "minecraft:poppy"),
        Map.entry("red_flower;type=blue_orchid", "minecraft:blue_orchid"),
        Map.entry("red_flower;type=orange_tulip", "minecraft:orange_tulip"),
        Map.entry("red_flower;type=pink_tulip", "minecraft:pink_tulip"),
        Map.entry("red_flower;type=red_tulip", "minecraft:red_tulip"),
        Map.entry("red_flower;type=white_tulip", "minecraft:white_tulip"),
        Map.entry("yellow_flower;type=dandelion", "minecraft:dandelion"),
        Map.entry("double_plant;facing=north,half=lower,variant=double_rose", "minecraft:rose_bush[half=lower]"),
        Map.entry("millenaire:leaves_appletree;age=0,check_decay=false,decayable=true", "millenaire_rewrite:leaves_appletree[age=0]"),
        Map.entry("millenaire:leaves_appletree;age=3,check_decay=false,decayable=true", "millenaire_rewrite:leaves_appletree[age=3]"),
        Map.entry("millenaire:leaves_olivetree;age=0,check_decay=false,decayable=true", "millenaire_rewrite:leaves_olivetree[age=0]"),
        Map.entry("millenaire:leaves_olivetree;age=3,check_decay=false,decayable=true", "millenaire_rewrite:leaves_olivetree[age=3]"),
        Map.entry("millenaire:leaves_pistachio;age=0,check_decay=false,decayable=true", "millenaire_rewrite:leaves_pistachio[age=0]"),
        Map.entry("millenaire:leaves_pistachio;age=3,check_decay=false,decayable=true", "millenaire_rewrite:leaves_pistachio[age=3]"),
        Map.entry("minecraft:stone;variant=diorite", "minecraft:diorite"),
        Map.entry("minecraft:snow_layer", "minecraft:snow")
    );

    private LegacyStates() {
    }

    @Nullable
    public static BlockState parse(@Nullable String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return null;
        }
        String key = legacy.trim().toLowerCase(Locale.ROOT);
        String modern = MAP.getOrDefault(key, MAP.getOrDefault(key.replace("minecraft:", ""), key.contains(";") ? null : key));
        if (modern == null) {
            MillenaireRewrite.LOGGER.warn("No 1.20 block state for legacy state {}", legacy);
            return null;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), modern, false).blockState();
        } catch (Exception e) {
            MillenaireRewrite.LOGGER.warn("Cannot parse block state {} (from {}): {}", modern, legacy, e.getMessage());
            return null;
        }
    }
}

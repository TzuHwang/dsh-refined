package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 颜色 -> PointType 对照表，从 millenaire/blocklist.txt 读取。
 *
 * 该文件由 tools/blocklist-converter 从 1.12 版转换而来，方块已经是 1.20.1 的状态字符串。
 */
public final class BlockList {

    /** 纯白：空气（图纸中的 "empty"） */
    public static final int EMPTY_COLOUR = 0xFFFFFF;

    private static Map<Integer, PointType> colourPoints;

    private BlockList() {
    }

    public static synchronized Map<Integer, PointType> get() {
        if (colourPoints == null) {
            colourPoints = load();
        }
        return colourPoints;
    }

    private static Map<Integer, PointType> load() {
        Map<Integer, PointType> map = new HashMap<>();
        int errors = 0;
        try {
            for (String raw : MillContent.readLines(MillContent.resolve("blocklist.txt"))) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//")) {
                    continue;
                }
                String[] p = line.split(";", -1);
                String[] rgb = p[3].split("/");
                int colour = (Integer.parseInt(rgb[0]) << 16) | (Integer.parseInt(rgb[1]) << 8) | Integer.parseInt(rgb[2]);
                String label = p[0];
                String stateString = p[1];
                // 原版 cost：costItem;costValues;costQuantity，没写时方块按 1 个自身计，特殊标记免费
                String costItem = p.length > 4 && !p[4].isBlank() ? p[4].trim() : null;
                Integer costQuantity = null;
                if (p.length > 6 && !p[6].isBlank()) {
                    costQuantity = Integer.parseInt(p[6].trim());
                }
                if (stateString.isEmpty()) {
                    map.put(colour, new PointType(label, label, null, false, costItem,
                        costQuantity == null ? 0 : costQuantity));
                    continue;
                }
                try {
                    BlockState state = BlockStateParser.parseForBlock(
                        BuiltInRegistries.BLOCK.asLookup(), stateString, false).blockState();
                    map.put(colour, new PointType(label, null, state, Boolean.parseBoolean(p[2]), costItem,
                        costQuantity != null ? costQuantity : state.isAir() ? 0 : 1));
                } catch (CommandSyntaxException e) {
                    errors++;
                    MillenaireRewrite.LOGGER.warn("blocklist: cannot parse block state '{}' for '{}': {}",
                        stateString, label, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read millenaire/blocklist.txt", e);
        }
        MillenaireRewrite.LOGGER.info("Loaded {} building plan colours ({} errors)", map.size(), errors);
        return map;
    }
}

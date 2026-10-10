package com.jasoncian.millenaire_rewrite.content;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.MillContent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 读取原版 goals/generic*&#47;*.txt 中的通用工作目标（收成、采矿）。
 */
public final class WorkGoalConfigs {

    public enum Kind { HARVEST, MINE }

    /**
     * @param name        目标名（小写），如 harvestwheathome
     * @param priority    原版优先级
     * @param cropType    收成：作物类型，如 wheat
     * @param harvestItems 收成：产出物品及概率（%）
     * @param sourceBlock 采矿：资源方块，如 minecraft:stone
     * @param loot        采矿：产出物品及数量
     */
    public record WorkGoalConfig(String name, Kind kind, int priority, @Nullable String cropType,
                                 List<Drop> harvestItems, @Nullable String sourceBlock, List<Drop> loot) {
    }

    /**
     * @param amount 收成时为概率（%），采矿时为数量
     */
    public record Drop(Item item, int amount) {
    }

    private static Map<String, WorkGoalConfig> configs;

    private WorkGoalConfigs() {
    }

    @Nullable
    public static synchronized WorkGoalConfig get(String goalName) {
        if (configs == null) {
            configs = new HashMap<>();
            load("genericharvesting", Kind.HARVEST);
            load("genericmining", Kind.MINE);
            MillenaireRewrite.LOGGER.info("Loaded {} work goal configs", configs.size());
        }
        return configs.get(goalName.toLowerCase(Locale.ROOT));
    }

    private static void load(String folder, Kind kind) {
        Path dir = MillContent.resolve("goals", folder);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                String name = file.getFileName().toString().replace(".txt", "").toLowerCase(Locale.ROOT);
                configs.put(name, read(name, kind, file));
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot read goals/{}", folder, e);
        }
    }

    private static WorkGoalConfig read(String name, Kind kind, Path file) throws IOException {
        int priority = 10;
        String cropType = null;
        String sourceBlock = null;
        List<Drop> harvest = new ArrayList<>();
        List<Drop> loot = new ArrayList<>();
        for (String raw : MillContent.readLines(file)) {
            String line = raw.trim();
            if (line.startsWith("//") || !line.contains("=")) {
                continue;
            }
            String[] kv = line.split("=", 2);
            String v = kv[1].trim();
            switch (kv[0].trim().toLowerCase(Locale.ROOT)) {
                case "priority" -> priority = Integer.parseInt(v);
                case "croptype" -> cropType = v.toLowerCase(Locale.ROOT);
                case "sourceblockstate" -> sourceBlock = v;
                case "harvestitem" -> addDrop(harvest, v);
                case "loot" -> addDrop(loot, v);
                default -> {
                }
            }
        }
        return new WorkGoalConfig(name, kind, priority, cropType, harvest, sourceBlock, loot);
    }

    private static void addDrop(List<Drop> drops, String value) {
        String[] parts = value.split(",");
        Item item = item(parts[0].trim());
        if (item != null) {
            drops.add(new Drop(item, parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1));
        }
    }

    /**
     * 原版物品名 -> 物品：先找原版同名物品，再找本模组物品；少数改名的手动对应
     */
    @Nullable
    public static Item item(String millenaireName) {
        String name = millenaireName.toLowerCase(Locale.ROOT);
        String renamed = switch (name) {
            case "seeds" -> "wheat_seeds";
            case "potato", "potatoes" -> "potato";
            case "carrots" -> "carrot";
            case "reeds" -> "sugar_cane";
            default -> name;
        };
        for (String namespace : new String[] {"minecraft", MillenaireRewrite.MOD_ID}) {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(namespace, renamed));
            if (item != Items.AIR) {
                return item;
            }
        }
        return null;
    }
}

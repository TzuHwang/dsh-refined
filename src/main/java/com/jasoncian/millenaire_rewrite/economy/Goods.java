package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.MillContent;
import com.mojang.brigadier.StringReader;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.ItemStackTheFlatteningFix;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原版 Millénaire 的商品（millenaire/itemlist.txt：键;1.12 物品 id;meta）换算成 1.20.1 物品。
 *
 * 原版物品用 Minecraft 自带的数据修复（1.12 meta 扁平化 + 之后的改名）；millenaire: 物品按名称
 * 与本模组物品对照（忽略大小写和下划线），对不上的写在 millenaire_economy/item_mapping.txt
 * （键 = 物品，可带 NBT，例如 akwardpotion = minecraft:potion{Potion:"minecraft:awkward"}）。
 */
public final class Goods {

    /** 1.12 扁平化后第一个数据版本 */
    private static final int FLATTENED_VERSION = 1451;

    /**
     * 一种商品：具体物品（可带 NBT），或 meta 为 -1 时的一类物品（任意原木等）
     */
    public record Good(String key, Item item, @Nullable CompoundTag nbt, @Nullable TagKey<Item> anyOf) {
        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) {
                return false;
            }
            if (anyOf != null) {
                return stack.is(anyOf);
            }
            return stack.is(item) && (nbt == null || NbtUtils.compareNbt(nbt, stack.getTag(), true));
        }

        public ItemStack create(int count) {
            ItemStack stack = new ItemStack(item, count);
            if (nbt != null) {
                stack.setTag(nbt.copy());
            }
            return stack;
        }
    }

    private static Map<String, Good> goods;
    private static List<String> unresolved;
    /** 对照表中标为 none 的商品：本移植版没有这件物品，交易数据里引用它们不算错误 */
    private static java.util.Set<String> unavailable;

    private Goods() {
    }

    @Nullable
    public static Good get(String key) {
        return all().get(key.toLowerCase(Locale.ROOT));
    }

    public static synchronized Map<String, Good> all() {
        if (goods == null) {
            load();
        }
        return goods;
    }

    public static boolean isUnavailable(String key) {
        all();
        return unavailable.contains(key.toLowerCase(Locale.ROOT));
    }

    /** 无法换算的商品（键 + 原 id），用于检查对照表 */
    public static List<String> unresolved() {
        all();
        return Collections.unmodifiableList(unresolved);
    }

    // ================ 读取 ================

    private static void load() {
        Map<String, Good> map = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Map<String, String> overrides = readOverrides();
        java.util.Set<String> none = new java.util.HashSet<>();
        overrides.entrySet().removeIf(e -> {
            if (e.getValue().equalsIgnoreCase("none")) {
                none.add(e.getKey());
                return true;
            }
            return false;
        });
        Map<String, Item> modItems = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id.getNamespace().equals(MillenaireRewrite.MOD_ID)) {
                modItems.put(normalise(id.getPath()), item);
            }
        }
        try {
            for (String raw : MillContent.readLines(MillContent.resolve("itemlist.txt"))) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("//")) {
                    continue;
                }
                String[] p = line.split(";");
                if (p.length < 2) {
                    continue;
                }
                String key = p[0].trim().toLowerCase(Locale.ROOT);
                String id = p[1].trim();
                int meta = p.length > 2 && !p[2].isBlank() ? Integer.parseInt(p[2].trim()) : 0;
                if (none.contains(key)) {
                    continue;
                }
                Good good = overrides.containsKey(key) ? parseItem(key, overrides.get(key)) : convert(key, id, meta, modItems);
                if (good == null) {
                    missing.add(key + " (" + id + ";" + meta + ")");
                } else {
                    map.put(key, good);
                }
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot read millenaire/itemlist.txt", e);
        }
        // 对照表里额外定义的商品（itemlist 没有的键）
        overrides.forEach((key, value) -> {
            if (!map.containsKey(key)) {
                Good good = parseItem(key, value);
                if (good != null) {
                    map.put(key, good);
                }
            }
        });
        goods = map;
        unresolved = missing;
        unavailable = none;
        MillenaireRewrite.LOGGER.info("Loaded {} goods ({} unresolved)", map.size(), missing.size());
    }

    @Nullable
    private static Good convert(String key, String id, int meta, Map<String, Item> modItems) {
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        if (id.startsWith("minecraft:")) {
            return convertVanilla(key, id, meta);
        }
        if (id.startsWith("millenaire:") && meta == 0) {
            Item item = modItems.get(normalise(id.substring("millenaire:".length())));
            return item == null ? null : new Good(key, item, null, null);
        }
        return null;
    }

    @Nullable
    private static Good convertVanilla(String key, String id, int meta) {
        if (meta == -1) {
            TagKey<Item> tag = switch (id) {
                case "minecraft:log", "minecraft:log2" -> ItemTags.LOGS;
                case "minecraft:planks" -> ItemTags.PLANKS;
                case "minecraft:wool" -> ItemTags.WOOL;
                case "minecraft:sapling" -> ItemTags.SAPLINGS;
                case "minecraft:leaves", "minecraft:leaves2" -> ItemTags.LEAVES;
                default -> null;
            };
            Good first = convertVanilla(key, id, 0);
            return first == null ? null : new Good(key, first.item(), null, tag);
        }
        String flattened = ItemStackTheFlatteningFix.updateItem(id, meta);
        String name = flattened != null ? flattened : id;
        Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.ITEM_NAME,
            new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(name)), FLATTENED_VERSION,
            SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        ResourceLocation location = ResourceLocation.tryParse(fixed.asString(name));
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(location);
        return item == Items.AIR ? null : new Good(key, item, null, null);
    }

    @Nullable
    private static Good parseItem(String key, String value) {
        try {
            ItemParser.ItemResult result = ItemParser.parseForItem(BuiltInRegistries.ITEM.asLookup(), new StringReader(value));
            return new Good(key, result.item().value(), result.nbt(), null);
        } catch (Exception e) {
            MillenaireRewrite.LOGGER.warn("item_mapping: cannot parse '{}' for good {}: {}", value, key, e.getMessage());
            return null;
        }
    }

    private static Map<String, String> readOverrides() {
        Map<String, String> map = new HashMap<>();
        var file = ModList.get().getModFileById(MillenaireRewrite.MOD_ID);
        Path path = file == null ? null : file.getFile().findResource("millenaire_economy", "item_mapping.txt");
        if (path == null || !Files.exists(path)) {
            return map;
        }
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("//") || eq < 0) {
                    continue;
                }
                map.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot read millenaire_economy/item_mapping.txt", e);
        }
        return map;
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}

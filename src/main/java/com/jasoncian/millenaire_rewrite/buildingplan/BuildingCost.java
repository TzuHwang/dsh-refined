package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.economy.Goods;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 原版 PngPlanLoader.computeCost：一级图纸要花的材料。
 *
 * 每格按 blocklist 的 costItem × costQuantity 计（没写时为方块本身 1 个）；
 * 木板、木棍按 4 个折 1 根原木，玻璃板按 16 片折 6 块玻璃。原木一律算「任意原木」，伐木工砍什么树都能用。
 */
public final class BuildingCost {

    /** 原版 Blocks.LOG meta -1：任意原木 */
    public static final Goods.Good ANY_WOOD = new Goods.Good("anywood", Items.OAK_LOG, null, ItemTags.LOGS);

    /** 任意颜色的羊毛 */
    public static final Goods.Good ANY_WOOL = new Goods.Good("anywool", Items.WHITE_WOOL, null, ItemTags.WOOL);

    private static final Map<BuildingPlan, Map<Goods.Good, Integer>> CACHE = new WeakHashMap<>();

    private BuildingCost() {
    }

    public static synchronized Map<Goods.Good, Integer> of(BuildingPlan plan) {
        return CACHE.computeIfAbsent(plan, BuildingCost::compute);
    }

    private static Map<Goods.Good, Integer> compute(BuildingPlan plan) {
        Map<Item, Integer> raw = new LinkedHashMap<>();
        Map<Goods.Good, Integer> cost = new LinkedHashMap<>();
        if (plan.plan == null) {
            return cost;
        }
        for (PointType[][] floor : plan.plan) {
            for (PointType[] row : floor) {
                for (PointType p : row) {
                    if (p == null || p.costQuantity() <= 0) {
                        continue;
                    }
                    if (p.costItem() != null && Goods.get(p.costItem()) != null) {
                        cost.merge(Goods.get(p.costItem()), p.costQuantity(), Integer::sum);
                        continue;
                    }
                    if ("anywood".equalsIgnoreCase(p.costItem())) {
                        cost.merge(ANY_WOOD, p.costQuantity(), Integer::sum);
                        continue;
                    }
                    Item item = resolve(p);
                    if (item != null && item != Items.AIR) {
                        raw.merge(item, p.costQuantity(), Integer::sum);
                    }
                }
            }
        }
        int wood = 0;
        int quarterWood = 0;
        for (Map.Entry<Item, Integer> e : raw.entrySet()) {
            Item item = e.getKey();
            int n = e.getValue();
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (item == Items.STICK || path.endsWith("_planks")) {
                quarterWood += n;
            } else if (new net.minecraft.world.item.ItemStack(item).is(ItemTags.LOGS)) {
                wood += n;
            } else if (new net.minecraft.world.item.ItemStack(item).is(ItemTags.WOOL)) {
                cost.merge(ANY_WOOL, n, Integer::sum);
            } else if (item == Items.GLASS_PANE) {
                cost.merge(good(Items.GLASS), (int) Math.max(1, Math.ceil(n * 6.0 / 16.0)), Integer::sum);
            } else {
                cost.merge(good(item), n, Integer::sum);
            }
        }
        wood += quarterWood > 0 ? (int) Math.max(1, Math.ceil(quarterWood / 4.0)) : 0;
        if (wood > 0) {
            cost.merge(ANY_WOOD, wood, Integer::sum);
        }
        return Collections.unmodifiableMap(cost);
    }

    private static Goods.Good good(Item item) {
        return new Goods.Good(BuiltInRegistries.ITEM.getKey(item).getPath(), item, null, null);
    }

    /** 1.12 物品名 -> 1.20.1 */
    private static final Map<String, Item> RENAMED = Map.of(
        "minecraft:planks", Items.OAK_PLANKS,
        "minecraft:log", Items.OAK_LOG,
        "minecraft:log2", Items.OAK_LOG,
        "minecraft:brick_block", Items.BRICKS,
        "minecraft:nether_brick", Items.NETHER_BRICKS,
        "minecraft:wool", Items.WHITE_WOOL,
        "item:banner_white", Items.WHITE_BANNER);

    /**
     * 材料物品；本模组的方块（彩绘砖、雕花砂岩等）村里没有人生产，暂时免费，免得工程永远等不到材料
     */
    @Nullable
    private static Item resolve(PointType p) {
        String id = p.costItem();
        Item item;
        if (id == null) {
            item = p.state() == null ? null : p.state().getBlock().asItem();
        } else if (RENAMED.containsKey(id.toLowerCase())) {
            item = RENAMED.get(id.toLowerCase());
        } else {
            ResourceLocation rl = ResourceLocation.tryParse(id.toLowerCase());
            item = rl == null || !rl.getNamespace().equals("minecraft") ? null : BuiltInRegistries.ITEM.get(rl);
        }
        if (item == null || item == Items.AIR
            || !BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft")) {
            return null;
        }
        return item;
    }
}

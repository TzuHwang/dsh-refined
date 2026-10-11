package com.jasoncian.millenaire_rewrite.entity.ai.generic;

import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 原版通用目标的各个类型（GoalGenericCrafting / Cooking / TendFurnace / TakeFromBuilding / Visit）。
 */
public final class GenericGoalTypes {

    private GenericGoalTypes() {
    }

    /**
     * 按配置类型创建目标；还没实现的类型返回 null
     */
    @Nullable
    public static GenericGoal create(GenericGoalConfig config) {
        return switch (config.type) {
            case "crafting" -> new Crafting(config);
            case "cooking" -> new Cooking(config);
            case "tendfurnace" -> new TendFurnace(config);
            case "takefrombuilding" -> new TakeFromBuilding(config);
            case "visit" -> new Visit(config);
            default -> null;
        };
    }

    // ================ 制作 ================

    /**
     * 原版 GoalGenericCrafting：用材料（身上 + 建筑里）在建筑的制作点做出产品，产品放进建筑
     */
    public static final class Crafting extends GenericGoal {
        private final Map<Goods.Good, Integer> input;
        private final Map<Goods.Good, Integer> output;

        Crafting(GenericGoalConfig config) {
            super(config);
            this.input = config.goodsAmounts("input");
            this.output = config.goodsAmounts("output");
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (output.isEmpty()) {
                return null;
            }
            for (Map.Entry<Goods.Good, Integer> in : input.entrySet()) {
                if (available(villager, level, b, in.getKey()) < in.getValue()) {
                    return null;
                }
            }
            return position(b, "crafting");
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            for (Map.Entry<Goods.Good, Integer> in : input.entrySet()) {
                if (available(villager, level, b, in.getKey()) < in.getValue()) {
                    return true;
                }
            }
            input.forEach((good, n) -> consume(villager, level, b, good, n));
            output.forEach((good, n) -> {
                int stored = BuildingStock.store(level, b, good, n);
                if (stored < n) {
                    villager.addGood(good, n - stored);
                }
            });
            return true;
        }
    }

    // ================ 熔炉 ================

    private static final Map<VillageGrowth.PlacedBuilding, Furnaces> FURNACES = new WeakHashMap<>();

    private record Furnaces(int level, List<BlockPos> positions) {
    }

    /**
     * 建筑里的熔炉（扫描占地，按建筑等级缓存，升级后重新扫描）
     */
    static List<BlockPos> furnaces(ServerLevel level, VillageGrowth.PlacedBuilding b) {
        Furnaces cached = FURNACES.get(b);
        if (cached == null || cached.level != b.level) {
            List<BlockPos> list = new ArrayList<>();
            BoundingBox f = b.footprint;
            for (BlockPos pos : BlockPos.betweenClosed(f.minX(), f.minY() - 2, f.minZ(), f.maxX(), f.maxY() + 2, f.maxZ())) {
                if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) {
                    list.add(pos.immutable());
                }
            }
            cached = new Furnaces(b.level, List.copyOf(list));
            FURNACES.put(b, cached);
        }
        return cached.positions;
    }

    @Nullable
    private static Container furnaceAt(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    /**
     * 原版 GoalGenericCooking：把要烤的东西（够 minimumToCook 个）放进建筑的熔炉，
     * 或者把烤好的东西（够 minimumToCook 个）取出放进建筑
     */
    public static final class Cooking extends GenericGoal {
        @Nullable
        private final Goods.Good itemToCook;
        private final int minimumToCook;

        Cooking(GenericGoalConfig config) {
            super(config);
            this.itemToCook = config.good("itemtocook");
            this.minimumToCook = config.intParam("minimumtocook", 16);
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (itemToCook == null) {
                return null;
            }
            int count = available(villager, level, b, itemToCook);
            for (BlockPos pos : furnaces(level, b)) {
                Container furnace = furnaceAt(level, pos);
                if (furnace == null) {
                    continue;
                }
                ItemStack in = furnace.getItem(0);
                if (count >= minimumToCook && (in.isEmpty() || itemToCook.matches(in) && in.getCount() < 32)) {
                    return pos;
                }
                if (furnace.getItem(2).getCount() >= minimumToCook) {
                    return pos;
                }
            }
            return null;
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (itemToCook == null || destPos == null) {
                return true;
            }
            Container furnace = furnaceAt(level, destPos);
            if (furnace == null) {
                return true;
            }
            ItemStack in = furnace.getItem(0);
            int count = available(villager, level, b, itemToCook);
            if (count > 0 && (in.isEmpty() || itemToCook.matches(in) && in.getCount() < 64)) {
                int room = in.isEmpty() ? 64 : 64 - in.getCount();
                int n = consume(villager, level, b, itemToCook, Math.min(room, count));
                if (n > 0) {
                    furnace.setItem(0, in.isEmpty() ? itemToCook.create(n) : in.copyWithCount(in.getCount() + n));
                }
            }
            ItemStack out = furnace.getItem(2);
            if (!out.isEmpty()) {
                int stored = BuildingStock.store(level, b, out, out.getCount());
                out.shrink(stored);
                furnace.setItem(2, out);
            }
            furnace.setChanged();
            return true;
        }
    }

    /**
     * 原版 GoalGenericTendFurnace：把建筑（或自己家、身上）的原木做成木板放进熔炉当燃料
     */
    public static final class TendFurnace extends GenericGoal {
        private final int minimumFuel;

        TendFurnace(GenericGoalConfig config) {
            super(config);
            this.minimumFuel = config.intParam("minimumfuel", 4);
        }

        private static final Goods.Good ANY_LOG = new Goods.Good("wood_any", Items.OAK_LOG, null, ItemTags.LOGS_THAT_BURN);

        private int logsAvailable(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            VillageGrowth.PlacedBuilding house = house(villager);
            return available(villager, level, b, ANY_LOG) + (house != null && house != b ? BuildingStock.count(level, house, ANY_LOG) : 0);
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            int logs = logsAvailable(villager, level, b);
            for (BlockPos pos : furnaces(level, b)) {
                Container furnace = furnaceAt(level, pos);
                if (furnace == null) {
                    continue;
                }
                ItemStack fuel = furnace.getItem(1);
                if (fuel.isEmpty() ? logs > 4 : fuel.is(ItemTags.PLANKS) && fuel.getCount() < 32 && logs * 4 >= minimumFuel) {
                    return pos;
                }
            }
            return null;
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            if (destPos == null) {
                return true;
            }
            Container furnace = furnaceAt(level, destPos);
            if (furnace == null) {
                return true;
            }
            ItemStack fuel = furnace.getItem(1);
            // 一次最多加到 64 块木板（16 根原木）
            int logsWanted = Math.min(16, (64 - fuel.getCount()) / 4);
            for (int i = 0; i < logsWanted; i++) {
                ItemStack log = takeLog(villager, level, b);
                if (log.isEmpty()) {
                    break;
                }
                Item planks = planksFor(log.getItem());
                ItemStack current = furnace.getItem(1);
                if (!current.isEmpty() && !current.is(planks)) {
                    // 已有别种木板：原木放回建筑
                    BuildingStock.store(level, b, log, 1);
                    break;
                }
                furnace.setItem(1, new ItemStack(planks, current.getCount() + 4));
            }
            furnace.setChanged();
            return true;
        }

        private ItemStack takeLog(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            List<VillageGrowth.PlacedBuilding> sources = new ArrayList<>();
            sources.add(b);
            VillageGrowth.PlacedBuilding house = house(villager);
            if (house != null && house != b) {
                sources.add(house);
            }
            for (VillageGrowth.PlacedBuilding source : sources) {
                for (Container chest : BuildingStock.chests(level, source)) {
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        ItemStack stack = chest.getItem(i);
                        if (stack.is(ItemTags.LOGS_THAT_BURN)) {
                            ItemStack one = stack.copyWithCount(1);
                            stack.shrink(1);
                            chest.setChanged();
                            return one;
                        }
                    }
                }
            }
            if (villager.takeGood(ANY_LOG, 1) > 0) {
                return new ItemStack(Items.OAK_LOG);
            }
            return ItemStack.EMPTY;
        }

        private static Item planksFor(Item log) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(log);
            String path = id.getPath().replace("stripped_", "").replace("_log", "_planks").replace("_wood", "_planks")
                .replace("_stem", "_planks").replace("_hyphae", "_planks");
            Item planks = BuiltInRegistries.ITEM.get(new ResourceLocation(id.getNamespace(), path));
            return planks == Items.AIR ? Items.OAK_PLANKS : planks;
        }
    }

    // ================ 取物与拜访 ================

    /**
     * 原版 GoalGenericTakeFromBuilding：从建筑里取物品带在身上（之后由送货目标带回家）
     */
    public static final class TakeFromBuilding extends GenericGoal {
        private final Map<Goods.Good, Integer> collect;
        private final int minimumPickup;

        TakeFromBuilding(GenericGoalConfig config) {
            super(config);
            this.collect = config.goodsAmounts("collect_good");
            this.minimumPickup = config.intParam("minimumpickup", 4);
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            int total = 0;
            for (Map.Entry<Goods.Good, Integer> c : collect.entrySet()) {
                int needed = c.getValue() - villager.countGood(c.getKey());
                if (needed > 0) {
                    total += Math.min(needed, BuildingStock.count(level, b, c.getKey()));
                }
            }
            return total >= minimumPickup ? position(b, "selling") : null;
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            collect.forEach((good, wanted) -> {
                int needed = wanted - villager.countGood(good);
                if (needed > 0) {
                    villager.addGood(good, BuildingStock.take(level, b, good, needed));
                }
            });
            return true;
        }
    }

    /**
     * 原版 GoalGenericVisit：去建筑的某个位置待一会，或去找正在做某类目标的村民（如小孩看农夫干活）
     */
    public static final class Visit extends GenericGoal {
        private final String targetPosition;
        private final List<String> targetVillagerGoals;
        @Nullable
        private MillVillager targetVillager;

        Visit(GenericGoalConfig config) {
            super(config);
            String pos = config.string("targetposition");
            this.targetPosition = pos == null ? "sleeping" : pos;
            List<String> goals = new ArrayList<>();
            config.all("targetvillagergoals").forEach(line -> {
                for (String g : line.split(",")) {
                    goals.add(g.trim().toLowerCase());
                }
            });
            this.targetVillagerGoals = List.copyOf(goals);
        }

        @Nullable
        private MillVillager findTarget(MillVillager villager) {
            Village village = village(villager);
            if (village == null) {
                return null;
            }
            List<MillVillager> targets = new ArrayList<>();
            for (MillVillager v : village.getActiveVillagers()) {
                MillGoal goal = v.getGoalManager().getCurrentGoal();
                if (v != villager && goal != null && goal.getTags().stream().anyMatch(targetVillagerGoals::contains)) {
                    targets.add(v);
                }
            }
            return targets.isEmpty() ? null : targets.get(villager.getRandom().nextInt(targets.size()));
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            if (!targetVillagerGoals.isEmpty()) {
                return findTarget(villager) != null;
            }
            return super.isPossibleSpecific(villager);
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            targetVillager = targetVillagerGoals.isEmpty() ? null : findTarget(villager);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            if (!targetVillagerGoals.isEmpty()) {
                MillVillager target = targetVillager != null ? targetVillager : findTarget(villager);
                return target == null ? null : GoalInformation.ofEntity(target);
            }
            return super.getDestination(villager);
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return targetVillager == null || !targetVillager.isRemoved();
        }

        @Nullable
        @Override
        protected BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            BlockPos pos = position(b, targetPosition);
            return villager.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > 25 ? pos : null;
        }

        @Override
        protected boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b) {
            return true;
        }
    }
}

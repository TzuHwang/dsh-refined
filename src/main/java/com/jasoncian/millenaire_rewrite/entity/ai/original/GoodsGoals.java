package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.content.VillagerTypes;
import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.CultureEconomy;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原版的物资流动目标：替家里取物资、送回家、把商店要的物资送去商店、从家里/市政厅取商店要的物资、捡地上的东西。
 * 这些目标让村民生产的东西流进商店，商店才有货卖。
 */
public final class GoodsGoals {

    /** 原版：上次做完后至少隔 2000 tick，除非能一次带超过 16 个 */
    private static final int DELAY = 2000;
    private static final int BULK = 16;

    private GoodsGoals() {
    }

    // ================ 工具 ================

    @Nullable
    private static VillagerTypes.VillagerType typeOf(MillVillager villager) {
        try {
            return VillagerTypes.get(VillageTypes.cultureFolder(villager.getCulture()), villager.getVillagerType());
        } catch (IOException e) {
            return null;
        }
    }

    /** 村民需要家里常备的物资（原版 requiresGoods） */
    static Map<Goods.Good, Integer> requiredGoods(MillVillager villager) {
        Map<Goods.Good, Integer> map = new LinkedHashMap<>();
        VillagerTypes.VillagerType type = typeOf(villager);
        if (type != null) {
            type.requiredGoods().forEach((key, n) -> {
                Goods.Good g = Goods.get(key);
                if (g != null) {
                    map.merge(g, n, Integer::sum);
                }
            });
        }
        return map;
    }

    /** 住在 house 里的村民 */
    static List<MillVillager> residents(Village village, VillageGrowth.PlacedBuilding house) {
        List<MillVillager> list = new ArrayList<>();
        for (MillVillager v : village.getActiveVillagers()) {
            if (!v.isRemoved() && v.getHousePos() != null && house.contains(v.getHousePos())) {
                list.add(v);
            }
        }
        return list;
    }

    /**
     * 建筑里可以拿走的数量（原版 nbGoodAvailable）：
     * 市政厅和经营这种商品的商店留下保留数量，住户自己需要的也留下
     */
    static int availableToTake(ServerLevel level, Village village, VillageGrowth.PlacedBuilding b, Goods.Good good) {
        int n = BuildingStock.count(level, b, good);
        if (n <= 0) {
            return 0;
        }
        CultureEconomy economy = CultureEconomy.get(VillageTypes.cultureFolder(village.getCulture()));
        CultureEconomy.TradeGood tradeGood = economy.goodFor(good.create(1));
        if (tradeGood != null) {
            CultureEconomy.Shop shop = economy.shop(village.getGrowth().shopOf(b));
            boolean tradedHere = shop != null && (shop.sells().contains(tradeGood) || shop.buys().contains(tradeGood));
            if (b.townHall || tradedHere) {
                n -= tradeGood.reserved();
            }
        }
        for (MillVillager resident : residents(village, b)) {
            n -= requiredGoods(resident).getOrDefault(good, 0);
        }
        return Math.max(n, 0);
    }

    /** 商店要村民送来的物资（shops/*.txt 的 deliverto） */
    static List<Goods.Good> shopNeeds(Village village, VillageGrowth.PlacedBuilding shop) {
        CultureEconomy.Shop s = CultureEconomy.get(VillageTypes.cultureFolder(village.getCulture()))
            .shop(village.getGrowth().shopOf(shop));
        return s == null ? List.of() : s.deliverTo();
    }

    static BlockPos sellingPos(VillageGrowth.PlacedBuilding b) {
        List<BlockPos> selling = b.points("sellingPos");
        return selling.isEmpty() ? OriginalGoal.pointOf(b, "sleepingPos") : selling.get(0);
    }

    // ================ 目标 ================

    /**
     * 有冷却与"一次能带很多就不必等"规则的目标
     */
    private abstract static class Errand extends OriginalGoal {
        private long lastDone = Long.MIN_VALUE / 2;
        @Nullable
        protected VillageGrowth.PlacedBuilding dest;

        Errand(String key) {
            super(key);
            this.range = 3.0;
            this.duration = 40;
        }

        protected boolean delayOver(MillVillager villager) {
            return villager.level().getGameTime() > lastDone + DELAY;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            lastDone = villager.level().getGameTime();
            dest = find(villager, true);
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return find(villager, false) != null;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth.PlacedBuilding b = dest != null ? dest : find(villager, false);
            return b == null ? null : GoalInformation.ofBuilding(sellingPos(b), b.origin);
        }

        /**
         * @param ignoreDelay 已经接受目标时为 true
         */
        @Nullable
        protected abstract VillageGrowth.PlacedBuilding find(MillVillager villager, boolean ignoreDelay);
    }

    /**
     * 原版 GoalGetGoodsForHousehold：家里某样物资少于需要量的一半时，去别的建筑拿，之后送回家
     */
    public static final class GetGoodsForHousehold extends Errand {

        public GetGoodsForHousehold() {
            super("getgoodshousehold");
        }

        /**
         * 原版：家里短缺（少于一半）的物资在各商店可拿的总数 × 20
         */
        @Override
        public int priority(MillVillager villager) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            if (village == null || house == null || !(villager.level() instanceof ServerLevel level)) {
                return 0;
            }
            int nb = 0;
            for (MillVillager resident : residents(village, house)) {
                for (Map.Entry<Goods.Good, Integer> need : requiredGoods(resident).entrySet()) {
                    if (BuildingStock.count(level, house, need.getKey()) < need.getValue() / 2) {
                        for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                            if (shop != house) {
                                nb += availableToTake(level, village, shop, need.getKey());
                            }
                        }
                    }
                }
            }
            return nb * 20;
        }

        @Nullable
        @Override
        protected VillageGrowth.PlacedBuilding find(MillVillager villager, boolean ignoreDelay) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            if (village == null || house == null || !(villager.level() instanceof ServerLevel level)) {
                return null;
            }
            boolean delayOver = ignoreDelay || delayOver(villager);
            for (MillVillager resident : residents(village, house)) {
                for (Map.Entry<Goods.Good, Integer> need : requiredGoods(resident).entrySet()) {
                    if (BuildingStock.count(level, house, need.getKey()) >= need.getValue() / 2) {
                        continue;
                    }
                    int total = 0;
                    for (VillageGrowth.PlacedBuilding b : village.getGrowth().getBuildings()) {
                        if (b == house) {
                            continue;
                        }
                        int n = availableToTake(level, village, b, need.getKey());
                        total += n;
                        if (n > 0 && (delayOver || total > BULK)) {
                            return b;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        protected boolean act(MillVillager villager) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            if (village == null || house == null || dest == null || dest == house
                || !(villager.level() instanceof ServerLevel level)) {
                return true;
            }
            for (MillVillager resident : residents(village, house)) {
                requiredGoods(resident).forEach((good, wanted) -> {
                    if (BuildingStock.count(level, house, good) < wanted) {
                        int n = Math.min(availableToTake(level, village, dest, good), wanted);
                        villager.addGood(good, BuildingStock.take(level, dest, good, n));
                    }
                });
            }
            return true;
        }

        @Override
        public MillGoal nextGoal(MillVillager villager) {
            return new DeliverGoodsHousehold();
        }
    }

    /**
     * 原版 GoalDeliverGoodsHousehold：把家人需要的物资放进家里（只由取物资目标接续，不会自己被选中）
     */
    public static final class DeliverGoodsHousehold extends OriginalGoal {

        public DeliverGoodsHousehold() {
            super("delivergoodshousehold");
            this.range = 3.0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 100;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return false;
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            VillageGrowth.PlacedBuilding house = house(villager);
            return house == null ? null : GoalInformation.ofBuilding(sellingPos(house), house.origin);
        }

        @Override
        protected boolean act(MillVillager villager) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            if (village == null || house == null || !(villager.level() instanceof ServerLevel level)) {
                return true;
            }
            for (MillVillager resident : residents(village, house)) {
                for (Goods.Good good : requiredGoods(resident).keySet()) {
                    int n = villager.countGood(good);
                    if (n > 0) {
                        int stored = BuildingStock.store(level, house, good, n);
                        villager.takeGood(good, stored);
                    }
                }
            }
            return true;
        }
    }

    /**
     * 原版 GoalDeliverResourcesShop：身上带着商店要的物资时送去商店（带得越多越优先）
     */
    public static final class DeliverResourcesShop extends Errand {

        public DeliverResourcesShop() {
            super("deliverresourcesshop");
        }

        @Override
        public int priority(MillVillager villager) {
            Village village = village(villager);
            if (village == null) {
                return 0;
            }
            int priority = 0;
            for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                for (Goods.Good good : shopNeeds(village, shop)) {
                    priority += villager.countGood(good) * 10;
                }
            }
            return priority;
        }

        @Nullable
        @Override
        protected VillageGrowth.PlacedBuilding find(MillVillager villager, boolean ignoreDelay) {
            Village village = village(villager);
            if (village == null) {
                return null;
            }
            boolean delayOver = ignoreDelay || delayOver(villager);
            for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                int total = 0;
                for (Goods.Good good : shopNeeds(village, shop)) {
                    int n = villager.countGood(good);
                    total += n;
                    if (n > 0 && (delayOver || total > BULK)) {
                        return shop;
                    }
                }
            }
            return null;
        }

        @Override
        protected boolean act(MillVillager villager) {
            Village village = village(villager);
            if (village == null || dest == null || !(villager.level() instanceof ServerLevel level)) {
                return true;
            }
            for (Goods.Good good : shopNeeds(village, dest)) {
                int n = villager.countGood(good);
                if (n > 0) {
                    villager.takeGood(good, BuildingStock.store(level, dest, good, n));
                }
            }
            return true;
        }
    }

    /**
     * 原版 GoalGetResourcesForShops：从自己家或市政厅拿别的商店要的物资（之后由送货目标送去商店）
     */
    public static final class GetResourcesForShops extends Errand {

        public GetResourcesForShops() {
            super("gethousethresources");
        }

        @Override
        public int priority(MillVillager villager) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            VillageGrowth.PlacedBuilding hall = townHall(villager);
            if (village == null || !(villager.level() instanceof ServerLevel level)) {
                return 0;
            }
            int priority = 0;
            for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                for (Goods.Good good : shopNeeds(village, shop)) {
                    if (house != null) {
                        priority += BuildingStock.count(level, house, good) * 5;
                    }
                    if (hall != null && hall != shop) {
                        priority += BuildingStock.count(level, hall, good) * 5;
                    }
                }
            }
            return priority;
        }

        @Nullable
        @Override
        protected VillageGrowth.PlacedBuilding find(MillVillager villager, boolean ignoreDelay) {
            Village village = village(villager);
            VillageGrowth.PlacedBuilding house = house(villager);
            VillageGrowth.PlacedBuilding hall = townHall(villager);
            if (village == null || !(villager.level() instanceof ServerLevel level)) {
                return null;
            }
            boolean delayOver = ignoreDelay || delayOver(villager);
            for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                int total = 0;
                for (Goods.Good good : shopNeeds(village, shop)) {
                    if (house != null && shop != house) {
                        int n = availableToTake(level, village, house, good);
                        total += n;
                        if (n > 0 && (delayOver || total > BULK)) {
                            return house;
                        }
                    }
                    if (hall != null && hall != shop) {
                        int n = availableToTake(level, village, hall, good);
                        total += n;
                        if (n > 0 && (delayOver || total > BULK)) {
                            return hall;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        protected boolean act(MillVillager villager) {
            Village village = village(villager);
            if (village == null || dest == null || !(villager.level() instanceof ServerLevel level)) {
                return true;
            }
            for (VillageGrowth.PlacedBuilding shop : village.getGrowth().getShops()) {
                if (shop == dest) {
                    continue;
                }
                for (Goods.Good good : shopNeeds(village, shop)) {
                    int n = availableToTake(level, village, dest, good);
                    if (n > 0) {
                        villager.addGood(good, BuildingStock.take(level, dest, good, n));
                    }
                }
            }
            return true;
        }
    }

    /**
     * 原版 GoalGatherGoods：捡起附近地上自己会收集的东西（collectgood，如苹果、树苗）
     */
    public static final class GatherGoods extends OriginalGoal {

        private static final int RANGE = 20;
        @Nullable
        private ItemEntity target;

        public GatherGoods() {
            super("gathergoods");
            this.range = 2.0;
            this.duration = 0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 500;
        }

        @Nullable
        private static ItemEntity closestItem(MillVillager villager) {
            VillagerTypes.VillagerType type = typeOf(villager);
            if (type == null || type.collectGoods().isEmpty()) {
                return null;
            }
            List<Goods.Good> goods = type.collectGoods().stream().map(Goods::get).filter(g -> g != null).toList();
            ItemEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ItemEntity item : villager.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(villager.blockPosition()).inflate(RANGE, 10, RANGE))) {
                ItemStack stack = item.getItem();
                if (goods.stream().anyMatch(g -> g.matches(stack))) {
                    double d = item.distanceToSqr(villager);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = item;
                    }
                }
            }
            return best;
        }

        @Override
        protected boolean isPossibleSpecific(MillVillager villager) {
            return closestItem(villager) != null;
        }

        @Override
        public void onAccept(MillVillager villager) {
            super.onAccept(villager);
            target = closestItem(villager);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            ItemEntity item = target != null ? target : closestItem(villager);
            return item == null ? null : GoalInformation.ofEntity(item);
        }

        @Override
        protected boolean isStillValidSpecific(MillVillager villager) {
            return target == null || target.isAlive();
        }

        @Override
        protected boolean act(MillVillager villager) {
            if (target != null && target.isAlive()) {
                villager.addToInventory(target.getItem(), target.getItem().getCount());
                target.discard();
            }
            return true;
        }
    }
}

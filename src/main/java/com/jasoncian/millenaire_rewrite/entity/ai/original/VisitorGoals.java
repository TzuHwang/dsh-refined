package com.jasoncian.millenaire_rewrite.entity.ai.original;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.content.ResidentSpawner;
import com.jasoncian.millenaire_rewrite.content.VillagerTypes;
import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版的访客（VisitorManager + visitorNightAction + GoalForeignMerchantKeepStall）：
 * 市集每晚召来外地商人（不超过摊位数），商人把货存进市集，白天守着摊位；
 * 建筑的 initial.visitor（如朝圣者）每晚有一半机会来；访客住超过 5 晚、商人货卖完就离开。
 */
public final class VisitorGoals {

    /** 原版：访客住几晚后离开 */
    private static final int MAX_NIGHTS = 5;
    /** 村庄 -> 上次处理访客的是第几天 */
    private static final Map<UUID, Long> LAST_NIGHT = new ConcurrentHashMap<>();

    private VisitorGoals() {
    }

    @Nullable
    private static VillagerTypes.VillagerType typeOf(MillVillager v) {
        try {
            return VillagerTypes.get(VillageTypes.cultureFolder(v.getCulture()), v.getVillagerType());
        } catch (IOException e) {
            return null;
        }
    }

    // ================ 每晚召来访客 ================

    /**
     * 由村庄成长每秒调用；入夜后每晚处理一次
     */
    public static void tick(ServerLevel level, Village village) {
        // 按时间判断入夜（isDay 要等下一个 tick 才更新）
        if (level.getDayTime() % 24000L < 13000L) {
            return;
        }
        long day = level.getDayTime() / 24000L;
        Long last = LAST_NIGHT.put(village.getVillageId(), day);
        if (last != null && last == day) {
            return;
        }
        String culture = VillageTypes.cultureFolder(village.getCulture());
        VillageGrowth growth = village.getGrowth();
        for (VillageGrowth.PlacedBuilding b : growth.getBuildings()) {
            if (growth.tagsOf(b).contains("market")) {
                callMerchant(level, village, culture, b);
            }
            callVisitors(level, village, culture, b);
        }
    }

    private static List<MillVillager> guests(Village village, VillageGrowth.PlacedBuilding b) {
        return GoodsGoals.residents(village, b);
    }

    /** 原版 updateMarket：商人少于摊位数时召来一个外地商人，货存进市集 */
    private static void callMerchant(ServerLevel level, Village village, String culture, VillageGrowth.PlacedBuilding market) {
        int stalls = market.points("stall").size();
        long merchants = guests(village, market).stream().filter(v -> {
            VillagerTypes.VillagerType t = typeOf(v);
            return t != null && t.isForeignMerchant();
        }).count();
        if (stalls == 0 || merchants >= stalls) {
            return;
        }
        VillagerTypes.VillagerType type = randomMerchant(culture, level);
        if (type == null) {
            return;
        }
        try {
            MillVillager merchant = ResidentSpawner.spawnResident(level, village, culture, type.key(),
                OriginalGoal.pointOf(market, "sleepingPos"));
            if (merchant != null) {
                type.merchantStock().forEach((key, n) -> {
                    Goods.Good g = Goods.get(key);
                    if (g != null) {
                        BuildingStock.store(level, market, g, n);
                    }
                });
                MillenaireRewrite.LOGGER.info("Village {}: merchant {} arrived at {}", village.getName(),
                    merchant.getFullName(), market.key);
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.warn("Cannot create merchant {}: {}", type.key(), e.getMessage());
        }
    }

    @Nullable
    private static VillagerTypes.VillagerType randomMerchant(String culture, ServerLevel level) {
        List<VillagerTypes.VillagerType> merchants = new ArrayList<>();
        try {
            for (VillagerTypes.VillagerType t : VillagerTypes.all(culture)) {
                if (t.isForeignMerchant()) {
                    merchants.add(t);
                }
            }
        } catch (IOException e) {
            return null;
        }
        int total = merchants.stream().mapToInt(t -> Math.max(1, t.chanceWeight())).sum();
        if (total == 0) {
            return null;
        }
        int roll = level.random.nextInt(total);
        for (VillagerTypes.VillagerType t : merchants) {
            roll -= Math.max(1, t.chanceWeight());
            if (roll < 0) {
                return t;
            }
        }
        return null;
    }

    /** 原版 updateVisitors：图纸的 initial.visitor 没住满时，每个空位有一半机会来人 */
    private static void callVisitors(ServerLevel level, Village village, String culture, VillageGrowth.PlacedBuilding b) {
        List<String> wanted;
        try {
            wanted = BuildingPlan.get(culture, b.key, b.variation, 0).visitors;
        } catch (IOException e) {
            return;
        }
        if (wanted.isEmpty()) {
            return;
        }
        List<MillVillager> present = guests(village, b);
        Map<String, Long> have = new java.util.HashMap<>();
        present.forEach(v -> have.merge(v.getVillagerType(), 1L, Long::sum));
        Map<String, Long> need = new java.util.HashMap<>();
        wanted.forEach(t -> need.merge(t.toLowerCase(), 1L, Long::sum));
        need.forEach((type, n) -> {
            for (long i = have.getOrDefault(type, 0L); i < n; i++) {
                if (level.random.nextBoolean()) {
                    try {
                        ResidentSpawner.spawnResident(level, village, culture, type, OriginalGoal.pointOf(b, "sleepingPos"));
                    } catch (IOException e) {
                        MillenaireRewrite.LOGGER.warn("Cannot create visitor {}: {}", type, e.getMessage());
                    }
                }
            }
        });
    }

    // ================ 访客的夜间动作 ================

    /**
     * 原版 visitorNightAction：住超过 5 晚离开；外地商人的货卖完了也离开
     */
    public static void nightAction(MillVillager visitor) {
        VillagerTypes.VillagerType type = typeOf(visitor);
        if (type == null || !type.isVisitor() || !(visitor.level() instanceof ServerLevel level)) {
            return;
        }
        visitor.setVisitorNights(visitor.getVisitorNights() + 1);
        boolean leave = visitor.getVisitorNights() > MAX_NIGHTS;
        if (!leave && type.isForeignMerchant()) {
            VillageGrowth.PlacedBuilding house = OriginalGoal.house(visitor);
            leave = house == null || type.merchantStock().keySet().stream().map(Goods::get)
                .noneMatch(g -> g != null && BuildingStock.count(level, house, g) > 0);
        }
        if (leave) {
            Village village = visitor.getHomeVillage();
            MillenaireRewrite.LOGGER.info("Visitor {} leaves the village", visitor.getFullName());
            if (village != null) {
                village.unregisterVillager(visitor);
            }
            visitor.discard();
        }
    }

    // ================ 守摊 ================

    /**
     * 原版 GoalForeignMerchantKeepStall：外地商人守着市集的摊位（每人一个），等玩家来买
     */
    public static final class KeepStall extends OriginalGoal {

        public KeepStall() {
            super("keepstall");
            this.range = 2.0;
            this.duration = 1200;
        }

        @Override
        public int priority(MillVillager villager) {
            return villager.getRandom().nextInt(50);
        }

        @Nullable
        private static BlockPos stallOf(MillVillager merchant) {
            VillageGrowth.PlacedBuilding market = house(merchant);
            Village village = village(merchant);
            if (market == null || village == null) {
                return null;
            }
            List<BlockPos> stalls = market.points("stall");
            List<MillVillager> merchants = new ArrayList<>(GoodsGoals.residents(village, market));
            merchants.sort(Comparator.comparingLong(MillVillager::getVillagerId));
            int index = merchants.indexOf(merchant);
            return index < 0 || index >= stalls.size() ? null : stalls.get(index);
        }

        @Nullable
        @Override
        public GoalInformation getDestination(MillVillager villager) {
            BlockPos stall = stallOf(villager);
            return stall == null ? null : GoalInformation.ofDestination(stall);
        }

        @Override
        protected boolean act(MillVillager villager) {
            var player = villager.level().getNearestPlayer(villager, 8);
            if (player != null) {
                villager.getLookControl().setLookAt(player);
            }
            // 原版：守够时间后每 tick 有 1/600 的机会去做别的事
            return villager.getRandom().nextInt(600) == 0;
        }
    }
}

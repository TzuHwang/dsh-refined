package com.jasoncian.millenaire_rewrite.entity.ai.generic;

import com.jasoncian.millenaire_rewrite.economy.BuildingStock;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.entity.ai.original.OriginalGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版 GoalGeneric：按配置找目标建筑（标签、市政厅或自己家），检查建筑/市政厅/全村的数量上限、
 * 同时进行的人数和重复间隔，到达后由子类执行具体动作。
 */
public abstract class GenericGoal extends OriginalGoal {

    protected final GenericGoalConfig config;
    /** 上次开始这个目标的游戏时间（原版 lastGoalTime） */
    private long lastStarted = Long.MIN_VALUE / 2;
    /** 这次的目标建筑 */
    @Nullable
    protected VillageGrowth.PlacedBuilding destBuilding;
    @Nullable
    protected BlockPos destPos;

    protected GenericGoal(GenericGoalConfig config) {
        super(config.key);
        this.config = config;
        this.leisure = config.leisure;
        this.sprint = config.sprint;
        this.range = config.range;
        this.duration = config.duration;
        this.tags.addAll(config.goalTags);
        this.tags.add(config.type);
        this.maxSimultaneousTotal = config.maxSimultaneousTotal > 0 ? config.maxSimultaneousTotal : -1;
        this.maxSimultaneousInBuilding = config.maxSimultaneousInBuilding > 0 ? config.maxSimultaneousInBuilding : -1;
        if (config.minimumHour >= 0) {
            this.minimumHour = config.minimumHour;
        }
        if (config.maximumHour >= 0) {
            this.maximumHour = config.maximumHour;
        }
    }

    @Override
    public int priority(MillVillager villager) {
        return config.priority + (config.priorityRandom > 0 ? villager.getRandom().nextInt(config.priorityRandom) : 0);
    }

    // ================ 目标建筑 ================

    /**
     * 原版 getBuildings：市政厅目标 -> 市政厅；有 buildingTag -> 带这个标签的建筑；否则 -> 自己家
     */
    protected List<VillageGrowth.PlacedBuilding> candidateBuildings(MillVillager villager) {
        Village village = village(villager);
        List<VillageGrowth.PlacedBuilding> result = new ArrayList<>();
        if (village == null) {
            return result;
        }
        VillageGrowth growth = village.getGrowth();
        if (config.townHallGoal) {
            VillageGrowth.PlacedBuilding hall = townHall(villager);
            if (hall != null) {
                result.add(hall);
            }
        } else if (config.buildingTag != null) {
            result.addAll(growth.buildingsWithTag(config.buildingTag));
        } else {
            VillageGrowth.PlacedBuilding house = house(villager);
            if (house != null) {
                result.add(house);
            }
        }
        if (config.requiredTag != null) {
            String required = config.requiredTag.toLowerCase();
            result.removeIf(b -> !growth.tagsOf(b).contains(required));
        }
        return result;
    }

    @Override
    protected boolean isPossibleSpecific(MillVillager villager) {
        if (config.reoccurDelay > 0 && villager.level().getGameTime() < lastStarted + config.reoccurDelay) {
            return false;
        }
        Village village = village(villager);
        if (village == null || !(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        VillageGrowth.PlacedBuilding hall = townHall(villager);
        for (Map.Entry<Goods.Good, Integer> limit : config.townHallLimit.entrySet()) {
            if (hall != null && BuildingStock.count(level, hall, limit.getKey()) > limit.getValue()) {
                return false;
            }
        }
        for (Map.Entry<Goods.Good, Integer> limit : config.villageLimit.entrySet()) {
            int total = 0;
            for (VillageGrowth.PlacedBuilding b : village.getGrowth().getBuildings()) {
                total += BuildingStock.count(level, b, limit.getKey());
            }
            if (total > limit.getValue()) {
                return false;
            }
        }
        if (config.itemsBalance.size() > 1 && hall != null
            && BuildingStock.count(level, hall, config.itemsBalance.get(0)) < BuildingStock.count(level, hall, config.itemsBalance.get(1))) {
            return false;
        }
        if (maxSimultaneousTotal > 0 && doingThis(village, villager, null) >= maxSimultaneousTotal) {
            return false;
        }
        return findDestination(villager, level) != null;
    }

    /**
     * 第一个可以做这个目标的建筑和位置；记在 destBuilding / destPos
     */
    @Nullable
    private BlockPos findDestination(MillVillager villager, ServerLevel level) {
        Village village = village(villager);
        for (VillageGrowth.PlacedBuilding b : candidateBuildings(villager)) {
            if (!underBuildingLimits(level, b)) {
                continue;
            }
            if (maxSimultaneousInBuilding > 0 && village != null && doingThis(village, villager, b) >= maxSimultaneousInBuilding) {
                continue;
            }
            BlockPos pos = destinationIn(villager, level, b);
            if (pos != null) {
                destBuilding = b;
                destPos = pos;
                return pos;
            }
        }
        destBuilding = null;
        destPos = null;
        return null;
    }

    private boolean underBuildingLimits(ServerLevel level, VillageGrowth.PlacedBuilding b) {
        for (Map.Entry<Goods.Good, Integer> limit : config.buildingLimit.entrySet()) {
            if (BuildingStock.count(level, b, limit.getKey()) > limit.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 正在做这个目标的其他村民（building 不为 null 时只算去这栋建筑的）
     */
    private int doingThis(Village village, MillVillager self, @Nullable VillageGrowth.PlacedBuilding building) {
        int n = 0;
        for (MillVillager v : village.getActiveVillagers()) {
            MillGoal current = v.getGoalManager().getCurrentGoal();
            if (v != self && current instanceof GenericGoal g && g.key.equals(key)
                && (building == null || g.destBuilding == building)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 在建筑 b 里做这个目标的位置；做不了时返回 null
     */
    @Nullable
    protected abstract BlockPos destinationIn(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b);

    @Nullable
    @Override
    public GoalInformation getDestination(MillVillager villager) {
        if (destPos == null && villager.level() instanceof ServerLevel level) {
            findDestination(villager, level);
        }
        return destPos == null ? null : destBuilding == null ? GoalInformation.ofDestination(destPos)
            : GoalInformation.ofBuilding(destPos, destBuilding.origin);
    }

    @Override
    public void onAccept(MillVillager villager) {
        super.onAccept(villager);
        lastStarted = villager.level().getGameTime();
        if (villager.level() instanceof ServerLevel level) {
            findDestination(villager, level);
        }
    }

    @Override
    protected final boolean act(MillVillager villager) {
        if (destBuilding == null || !(villager.level() instanceof ServerLevel level)) {
            return true;
        }
        villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return perform(villager, level, destBuilding);
    }

    /**
     * 到达并等够时间后的动作
     *
     * @return true 表示完成
     */
    protected abstract boolean perform(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b);

    // ================ 手持物品 ================

    @Override
    public ItemStack[] getHeldItemsTraveling(MillVillager villager) {
        return stacks(config.heldItems);
    }

    @Override
    public ItemStack[] getHeldItemsDestination(MillVillager villager) {
        return stacks(config.heldItemsDestination.isEmpty() ? config.heldItems : config.heldItemsDestination);
    }

    @Override
    public ItemStack[] getHeldItemsOffHandTraveling(MillVillager villager) {
        return stacks(config.heldItemsOffHand);
    }

    @Override
    public ItemStack[] getHeldItemsOffHandDestination(MillVillager villager) {
        return stacks(config.heldItemsOffHandDestination.isEmpty() ? config.heldItemsOffHand
            : config.heldItemsOffHandDestination);
    }

    private static ItemStack[] stacks(List<Goods.Good> goods) {
        return goods.stream().map(g -> g.create(1)).toArray(ItemStack[]::new);
    }

    // ================ 工具 ================

    /**
     * 建筑里的点位：crafting / sleeping / selling / leasure / defending / shelter（原版 PosType）
     */
    protected static BlockPos position(VillageGrowth.PlacedBuilding b, String posType) {
        String point = switch (posType.toLowerCase()) {
            case "sleeping" -> "sleepingPos";
            case "selling" -> "sellingPos";
            case "leasure", "leisure" -> "leisurePos";
            case "defending" -> "defendingPos";
            case "shelter" -> "shelterPos";
            default -> "craftingPos";
        };
        for (String type : new String[] {point, "craftingPos", "sleepingPos", "sellingPos"}) {
            List<BlockPos> points = b.points(type);
            if (!points.isEmpty()) {
                return points.get(0);
            }
        }
        return b.origin;
    }

    /** 村民身上加建筑里的数量 */
    protected static int available(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b, Goods.Good good) {
        return villager.countGood(good) + BuildingStock.count(level, b, good);
    }

    /** 先从村民身上拿，不够再从建筑里拿 */
    protected static int consume(MillVillager villager, ServerLevel level, VillageGrowth.PlacedBuilding b, Goods.Good good,
                                 int n) {
        int taken = villager.takeGood(good, n);
        if (taken < n) {
            taken += BuildingStock.take(level, b, good, n - taken);
        }
        return taken;
    }
}

package com.jasoncian.millenaire_rewrite.entity.ai.work;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.content.VillagerTypes;
import com.jasoncian.millenaire_rewrite.content.WorkGoalConfigs;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 按村民类型配置中的 goal= 列表创建村民的目标（原版 VillagerType.goals，另外所有村民都会睡觉）。
 * 生活目标：睡觉、回家休息、社交、聊天（LifeGoals）。
 * 目前支持：收成（genericharvesting）、采矿（genericmining）、砍树（chopTrees）、送货（bringBackResourcesHome）。
 * 种植类目标（plantwheathome 等）由收成目标在收成后立即补种代替。
 */
public final class WorkGoals {

    private WorkGoals() {
    }

    /** 还没有实现的原版目标（每个只记一次日志） */
    private static final java.util.Set<String> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static List<MillGoal> forVillager(MillVillager villager) {
        List<MillGoal> goals = new ArrayList<>();
        goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.Sleep());
        String type = villager.getVillagerType();
        if (type.isEmpty()) {
            goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.GoRest());
            return goals;
        }
        VillagerTypes.VillagerType config;
        try {
            config = VillagerTypes.get(VillageTypes.cultureFolder(villager.getCulture()), type);
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.warn("Cannot load villager type {} for work goals", type);
            return goals;
        }
        for (String name : config.goals()) {
            switch (name) {
                case "gorest" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.GoRest());
                case "gosocialise" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.GoSocialise());
                case "chat" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.Chat());
                case "getgoodshousehold" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.GoodsGoals.GetGoodsForHousehold());
                case "delivergoodshousehold" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.GoodsGoals.DeliverGoodsHousehold());
                case "deliverresourcesshop" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.GoodsGoals.DeliverResourcesShop());
                case "gethousethresources" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.GoodsGoals.GetResourcesForShops());
                case "breed" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.WorkSpecials.BreedAnimals());
                case "shearsheep" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.WorkSpecials.ShearSheep());
                case "plantsaplings" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.WorkSpecials.PlantSaplings());
                case "plantsugarcane" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.WorkSpecials.SugarCane(false));
                case "harvestsugarcane" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.WorkSpecials.SugarCane(true));
                case "huntmonster" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.CombatGoals.HuntMonster());
                case "becomeadult" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.FamilyGoals.BecomeAdult());
                case "gathergoods" -> goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.GoodsGoals.GatherGoods());
                case "choptrees" -> goals.add(new GoalChopTrees());
                case "bringbackresourceshome" -> goals.add(new GoalDeliver());
                default -> {
                    WorkGoalConfigs.WorkGoalConfig work = WorkGoalConfigs.get(name);
                    var generic = com.jasoncian.millenaire_rewrite.entity.ai.generic.GenericGoalConfig.get(name);
                    var genericGoal = generic == null ? null
                        : com.jasoncian.millenaire_rewrite.entity.ai.generic.GenericGoalTypes.create(generic);
                    if (genericGoal == null && generic != null) {
                        genericGoal = com.jasoncian.millenaire_rewrite.entity.ai.generic.FarmGoalTypes.create(generic);
                    }
                    // 采矿沿用 GoalMine；收成改用原版的通用收成（收完不补种，由种植目标补种）
                    if (work != null && work.kind() == WorkGoalConfigs.Kind.MINE) {
                        goals.add(new GoalMine(work));
                    } else if (genericGoal != null) {
                        goals.add(genericGoal);
                    } else if (MISSING.add(name)) {
                        MillenaireRewrite.LOGGER.info("Villager goal not implemented yet: {} ({})", name, type);
                    }
                }
            }
        }
        if (goals.size() == 1) {
            goals.add(new com.jasoncian.millenaire_rewrite.entity.ai.original.LifeGoals.GoRest());
        }
        return goals;
    }
}

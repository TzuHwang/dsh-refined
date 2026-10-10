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
 * 按村民类型配置中的 goal= 列表创建工作目标。
 * 目前支持：收成（genericharvesting）、采矿（genericmining）、砍树（chopTrees）、送货（bringBackResourcesHome）。
 * 种植类目标（plantwheathome 等）由收成目标在收成后立即补种代替。
 */
public final class WorkGoals {

    private WorkGoals() {
    }

    public static List<MillGoal> forVillager(MillVillager villager) {
        List<MillGoal> goals = new ArrayList<>();
        String type = villager.getVillagerType();
        if (type.isEmpty()) {
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
                case "choptrees" -> goals.add(new GoalChopTrees());
                case "bringbackresourceshome" -> goals.add(new GoalDeliver());
                default -> {
                    WorkGoalConfigs.WorkGoalConfig work = WorkGoalConfigs.get(name);
                    if (work != null) {
                        goals.add(work.kind() == WorkGoalConfigs.Kind.HARVEST ? new GoalHarvest(work) : new GoalMine(work));
                    }
                }
            }
        }
        return goals;
    }
}

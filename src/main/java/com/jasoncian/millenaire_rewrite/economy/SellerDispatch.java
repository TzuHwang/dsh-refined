package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.core.ModItems;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.entity.ai.GoalInformation;
import com.jasoncian.millenaire_rewrite.entity.ai.MillGoal;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派卖家（原版 Building.updateSeller + GoalBeSeller）：玩家站到商店的贩卖点（没有贩卖点时为睡觉点）
 * 3 格内时，村庄派离那里最近的卖家过去；玩家离开 7 格后卖家回去做别的事。
 */
public final class SellerDispatch {

    /** 原版：玩家离贩卖点多近时派卖家 */
    private static final double CALL_RANGE = 3.0;
    /** 原版 GoalBeSeller.sellingRadius：玩家离开这个距离后卖家离开 */
    private static final double SELLING_RADIUS = 7.0;

    /** 村庄 -> 正在卖东西的卖家 */
    private static final Map<UUID, BeSeller> ACTIVE = new ConcurrentHashMap<>();

    private SellerDispatch() {
    }

    /**
     * 每秒由村庄成长调用一次
     */
    public static void tick(ServerLevel level, Village village) {
        BeSeller current = ACTIVE.get(village.getVillageId());
        if (current != null) {
            if (current.seller.isRemoved() || current.seller.getGoalManager().getCurrentGoal() != current) {
                ACTIVE.remove(village.getVillageId());
            }
            return;
        }
        VillageGrowth growth = village.getGrowth();
        for (Player player : level.players()) {
            if (player.isSpectator() || growth.getReputation(player.getUUID()) < VillageGrowth.MIN_REPUTATION_FOR_TRADE) {
                continue;
            }
            BlockPos place = sellingPlaceNear(growth, player);
            if (place == null) {
                continue;
            }
            MillVillager seller = nearestSeller(village, place);
            if (seller == null) {
                return;
            }
            BeSeller goal = new BeSeller(village.getVillageId(), place, seller);
            ACTIVE.put(village.getVillageId(), goal);
            seller.getGoalManager().forceGoal(goal);
            player.sendSystemMessage(Component.translatableWithFallback("message.millenaire_rewrite.seller_coming",
                "%s is coming to serve you.", seller.getName()));
            return;
        }
    }

    @Nullable
    private static BlockPos sellingPlaceNear(VillageGrowth growth, Player player) {
        for (VillageGrowth.PlacedBuilding shop : growth.getShops()) {
            List<BlockPos> spots = shop.points("sellingPos").isEmpty() ? shop.points("sleepingPos") : shop.points("sellingPos");
            for (BlockPos spot : spots) {
                if (player.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) < CALL_RANGE * CALL_RANGE) {
                    return spot;
                }
            }
        }
        return null;
    }

    @Nullable
    private static MillVillager nearestSeller(Village village, BlockPos place) {
        MillVillager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MillVillager v : village.getActiveVillagers()) {
            if (v.isRemoved() || v.isChild() || !VillageTrade.isSeller(v)) {
                continue;
            }
            double d = v.distanceToSqr(place.getX() + 0.5, place.getY(), place.getZ() + 0.5);
            if (d < bestDistance) {
                bestDistance = d;
                best = v;
            }
        }
        return best;
    }

    /**
     * 原版 GoalBeSeller：走到贩卖点，手拿铜币和钱袋，玩家在附近时一直等着
     */
    public static final class BeSeller extends MillGoal {

        private static final ItemStack[] DENIER = {new ItemStack(ModItems.DENIER.get())};
        private static final ItemStack[] PURSE = {new ItemStack(ModItems.PURSE.get())};

        private final UUID villageId;
        private final BlockPos place;
        private final MillVillager seller;

        BeSeller(UUID villageId, BlockPos place, MillVillager seller) {
            super("beseller");
            this.villageId = villageId;
            this.place = place;
            this.seller = seller;
            this.leisure = false;
            this.range = 2.0;
        }

        @Override
        public int priority(MillVillager villager) {
            return 0;
        }

        @Override
        public boolean isPossible(MillVillager villager) {
            return false;
        }

        @Override
        public GoalInformation getDestination(MillVillager villager) {
            return GoalInformation.ofDestination(place);
        }

        @Override
        public boolean isStillValid(MillVillager villager) {
            Player player = villager.level().getNearestPlayer(place.getX() + 0.5, place.getY(), place.getZ() + 0.5,
                SELLING_RADIUS, false);
            return player != null;
        }

        @Override
        public boolean performAction(MillVillager villager) {
            // 站着等玩家交易，直到玩家离开（isStillValid 变为 false）
            Player player = villager.level().getNearestPlayer(villager, SELLING_RADIUS);
            if (player != null) {
                villager.getLookControl().setLookAt(player);
            }
            return false;
        }

        @Override
        public ItemStack[] getHeldItemsDestination(MillVillager villager) {
            return DENIER;
        }

        @Override
        public ItemStack[] getHeldItemsOffHandDestination(MillVillager villager) {
            return PURSE;
        }

        @Override
        public void onFail(MillVillager villager) {
            release(villager);
        }

        @Override
        public void onComplete(MillVillager villager) {
            release(villager);
        }

        @Override
        public void onInterrupt(MillVillager villager) {
            release(villager);
        }

        private void release(MillVillager villager) {
            if (ACTIVE.get(villageId) == this) {
                ACTIVE.remove(villageId);
                Player player = villager.level().getNearestPlayer(villager, 17.0);
                if (player != null) {
                    player.sendSystemMessage(Component.translatableWithFallback("message.millenaire_rewrite.trade_complete",
                        "%s goes back to work.", villager.getName()));
                }
            }
        }
    }
}

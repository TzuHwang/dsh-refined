package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.util.CurrencyUtils;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 与村庄商店交易（原版 Building.computeShopGoods + ContainerTrade.slotClick）。
 *
 * 村庄卖给玩家：商店 sells 的商品，库存为建筑箱子里的数量减去保留数量；
 * 村庄向玩家收购：商店 buys，加上玩家身上带着的 buysOptional 商品。
 * 价格以铜币计，村庄类型可以覆盖 traded_goods 的价格；交易额计入玩家在村庄的声望。
 */
public final class VillageTrade {

    /** 原版：卖家离商店的贩卖点或睡觉点多近才能开店 */
    private static final double SHOP_RANGE = 5.0;

    /**
     * 一项交易：villageSells 为 true 时是村庄卖给玩家
     *
     * @param stock 村庄卖出时为可卖数量（autoGenerate 商品为 -1，表示不限）；收购时为 0
     */
    public record Offer(boolean villageSells, String key, ItemStack item, int price, int stock, int minReputation) {
        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(villageSells);
            buf.writeUtf(key);
            buf.writeItem(item);
            buf.writeVarInt(price);
            buf.writeVarInt(stock);
            buf.writeInt(minReputation);
        }

        public static Offer read(FriendlyByteBuf buf) {
            return new Offer(buf.readBoolean(), buf.readUtf(), buf.readItem(), buf.readVarInt(), buf.readVarInt(),
                buf.readInt());
        }
    }

    private VillageTrade() {
    }

    // ================ 找商店 ================

    /**
     * 卖家所在的商店：卖家在贩卖点或睡觉点附近，或者就在商店建筑里
     */
    @Nullable
    public static VillageGrowth.PlacedBuilding shopAt(Village village, MillVillager seller) {
        VillageGrowth growth = village.getGrowth();
        VillageGrowth.PlacedBuilding inside = null;
        for (VillageGrowth.PlacedBuilding b : growth.getShops()) {
            List<BlockPos> spots = new ArrayList<>(b.points("sellingPos"));
            spots.addAll(b.points("sleepingPos"));
            for (BlockPos spot : spots) {
                if (seller.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) < SHOP_RANGE * SHOP_RANGE) {
                    return b;
                }
            }
            if (inside == null && b.contains(seller.blockPosition())) {
                inside = b;
            }
        }
        return inside;
    }

    public static boolean isSeller(MillVillager villager) {
        try {
            var type = com.jasoncian.millenaire_rewrite.content.VillagerTypes.get(
                com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes.cultureFolder(villager.getCulture()),
                villager.getVillagerType());
            return type != null && type.tags().contains("seller");
        } catch (IOException e) {
            return false;
        }
    }

    // ================ 交易清单 ================

    public static List<Offer> offers(ServerLevel level, Village village, VillageGrowth.PlacedBuilding shopBuilding,
                                     Player player) {
        List<Offer> offers = new ArrayList<>();
        CultureEconomy economy = CultureEconomy.get(VillageTypes.cultureFolder(village.getCulture()));
        CultureEconomy.Shop shop = economy.shop(village.getGrowth().shopOf(shopBuilding));
        if (shop == null) {
            return offers;
        }
        VillageTypes.VillageType type = villageType(village);
        for (CultureEconomy.TradeGood g : shop.sells()) {
            int price = sellingPrice(type, g);
            if (price > 0) {
                int stock = g.autoGenerate() ? -1 : BuildingStock.available(level, shopBuilding, g, true);
                offers.add(new Offer(true, g.key(), g.good().create(1), price, stock, g.minReputation()));
            }
        }
        List<CultureEconomy.TradeGood> buys = new ArrayList<>(shop.buys());
        for (CultureEconomy.TradeGood g : shop.buysOptional()) {
            if (countInInventory(player, g.good()) > 0 && !buys.contains(g)) {
                buys.add(g);
            }
        }
        for (CultureEconomy.TradeGood g : buys) {
            int price = buyingPrice(type, g);
            if (price > 0) {
                offers.add(new Offer(false, g.key(), g.good().create(1), price, 0, g.minReputation()));
            }
        }
        return offers;
    }

    @Nullable
    private static VillageTypes.VillageType villageType(Village village) {
        try {
            return VillageTypes.get(village.getCulture(), village.getGrowth().getVillageTypeFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static int sellingPrice(@Nullable VillageTypes.VillageType type, CultureEconomy.TradeGood g) {
        return type != null && type.sellingPrices().containsKey(g.key()) ? type.sellingPrices().get(g.key()) : g.sellingPrice();
    }

    private static int buyingPrice(@Nullable VillageTypes.VillageType type, CultureEconomy.TradeGood g) {
        return type != null && type.buyingPrices().containsKey(g.key()) ? type.buyingPrices().get(g.key()) : g.buyingPrice();
    }

    // ================ 成交 ================

    /**
     * 执行一笔交易（原版 ContainerTrade.slotClick）
     *
     * @param requested 想买卖的数量（单击 1、右键 8、Shift 64）
     * @return 实际成交的数量；0 时 problem 说明原因
     */
    public static Result execute(ServerLevel level, Village village, VillageGrowth.PlacedBuilding shopBuilding,
                                 Player player, boolean villageSells, String key, int requested) {
        Offer offer = offers(level, village, shopBuilding, player).stream()
            .filter(o -> o.villageSells() == villageSells && o.key().equals(key)).findFirst().orElse(null);
        CultureEconomy economy = CultureEconomy.get(VillageTypes.cultureFolder(village.getCulture()));
        CultureEconomy.TradeGood good = economy.good(key);
        if (offer == null || good == null) {
            return new Result(0, "unknown");
        }
        VillageGrowth growth = village.getGrowth();
        if (growth.getReputation(player.getUUID()) < offer.minReputation()) {
            return new Result(0, "reputation");
        }
        int n = requested;
        if (villageSells) {
            int money = CurrencyUtils.getPlayerTotalCurrency(player);
            n = Math.min(n, money / offer.price());
            if (offer.stock() >= 0) {
                n = Math.min(n, offer.stock());
            }
            n = Math.min(n, roomFor(player, good.good()));
            if (n <= 0) {
                return new Result(0, money < offer.price() ? "money" : offer.stock() == 0 ? "stock" : "inventory");
            }
            if (!CurrencyUtils.deductCurrencyFromPlayer(player, offer.price() * n)) {
                return new Result(0, "money");
            }
            if (!good.autoGenerate()) {
                BuildingStock.take(level, shopBuilding, good.good(), n);
            }
            giveItems(player, good.good(), n);
            growth.adjustReputation(player.getUUID(), offer.price() * n);
        } else {
            n = Math.min(n, countInInventory(player, good.good()));
            if (n <= 0) {
                return new Result(0, "nothing");
            }
            n = BuildingStock.store(level, shopBuilding, good.good(), n);
            if (n <= 0) {
                return new Result(0, "full");
            }
            removeItems(player, good.good(), n);
            CurrencyUtils.giveCurrencyToPlayer(player, offer.price() * n);
            growth.adjustReputation(player.getUUID(), offer.price() * n);
        }
        MillenaireRewrite.LOGGER.debug("{} {} {} x{} at {} for {} each", player.getName().getString(),
            villageSells ? "bought" : "sold", key, n, shopBuilding.key, offer.price());
        return new Result(n, null);
    }

    public record Result(int count, @Nullable String problem) {
    }

    // ================ 玩家背包 ================

    public static int countInInventory(Player player, Goods.Good good) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (good.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int roomFor(Player player, Goods.Good good) {
        ItemStack template = good.create(1);
        int max = template.getMaxStackSize();
        int room = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                room += max;
            } else if (ItemStack.isSameItemSameTags(stack, template)) {
                room += max - stack.getCount();
            }
        }
        return room;
    }

    private static void giveItems(Player player, Goods.Good good, int n) {
        int max = good.create(1).getMaxStackSize();
        while (n > 0) {
            int count = Math.min(n, max);
            ItemStack stack = good.create(count);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            n -= count;
        }
    }

    private static void removeItems(Player player, Goods.Good good, int n) {
        for (ItemStack stack : player.getInventory().items) {
            if (n <= 0) {
                break;
            }
            if (good.matches(stack)) {
                int t = Math.min(n, stack.getCount());
                stack.shrink(t);
                n -= t;
            }
        }
    }
}

package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.core.ModItems;
import com.jasoncian.millenaire_rewrite.entity.MillVillager;
import com.jasoncian.millenaire_rewrite.village.Village;
import com.jasoncian.millenaire_rewrite.village.VillageGrowth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 用原版村民交易界面与村庄商店交易。
 *
 * 村庄卖给玩家：付铜币/银币/金币，换商品（可交易次数为商店库存）；村庄收购：付商品，换钱币。
 * 价格换算成最多两种钱币（原版界面只有两个付款格）：金 + 银时铜的零头进位成 1 银。
 * 成交后从建筑箱子取货或存货，交易额计入声望。
 */
public class VillageMerchant implements Merchant {

    /** 收购没有次数限制（箱子满了时在成交时处理） */
    private static final int UNLIMITED = 9999;

    private final ServerLevel level;
    private final Village village;
    private final VillageGrowth.PlacedBuilding building;
    @Nullable
    private final MillVillager seller;
    @Nullable
    private Player tradingPlayer;
    private MerchantOffers offers = new MerchantOffers();
    /** 与 offers 同序：每项交易对应的商店商品 */
    private final List<VillageTrade.Offer> sources = new ArrayList<>();
    /** 外地商人：只卖自己带来的货（存在市集里），价格为 foreignMerchantPrice */
    private boolean foreign;

    public VillageMerchant(ServerLevel level, Village village, VillageGrowth.PlacedBuilding building,
                           @Nullable MillVillager seller) {
        this.level = level;
        this.village = village;
        this.building = building;
        this.seller = seller;
    }

    /**
     * 卖家站在商店里时打开原版交易界面（原版 interactSpecial）
     *
     * @return 是否处理了这次右键（不是卖家或不在商店里时为 false）
     */
    public static boolean openFor(ServerPlayer player, MillVillager seller) {
        Village village = seller.getHomeVillage();
        if (village != null && openForeign(player, village, seller)) {
            return true;
        }
        if (village == null || !VillageTrade.isSeller(seller)) {
            return false;
        }
        VillageGrowth.PlacedBuilding shop = VillageTrade.shopAt(village, seller);
        if (shop == null) {
            return false;
        }
        if (village.getGrowth().getReputation(player.getUUID()) < VillageGrowth.MIN_REPUTATION_FOR_TRADE) {
            player.sendSystemMessage(Component.translatableWithFallback("message.millenaire_rewrite.seller_boycott",
                "%s refuses to trade with you.", seller.getName()));
            return true;
        }
        new VillageMerchant(player.serverLevel(), village, shop, seller).open(player, seller.getName());
        return true;
    }

    /**
     * 外地商人（原版 tag foreignmerchant）：右键打开他带来的货
     */
    private static boolean openForeign(ServerPlayer player, Village village, MillVillager merchant) {
        com.jasoncian.millenaire_rewrite.content.VillagerTypes.VillagerType type;
        try {
            type = com.jasoncian.millenaire_rewrite.content.VillagerTypes.get(
                com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes.cultureFolder(merchant.getCulture()),
                merchant.getVillagerType());
        } catch (java.io.IOException e) {
            return false;
        }
        if (!type.isForeignMerchant()) {
            return false;
        }
        VillageGrowth.PlacedBuilding market = village.getGrowth().getBuildings().stream()
            .filter(b -> !b.subBuilding && merchant.getHousePos() != null && b.contains(merchant.getHousePos())
                && village.getGrowth().tagsOf(b).contains("market"))
            .findFirst().orElse(null);
        if (market == null) {
            return false;
        }
        VillageMerchant m = new VillageMerchant(player.serverLevel(), village, market, merchant);
        m.foreign = true;
        m.open(player, merchant.getName());
        return true;
    }

    /**
     * 外地商人的货：商人类型的 merchantstock，按文化的 foreignMerchantPrice 卖，库存为市集里的数量
     */
    private List<VillageTrade.Offer> foreignOffers() {
        List<VillageTrade.Offer> list = new ArrayList<>();
        if (seller == null) {
            return list;
        }
        try {
            String culture = com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes.cultureFolder(seller.getCulture());
            var type = com.jasoncian.millenaire_rewrite.content.VillagerTypes.get(culture, seller.getVillagerType());
            CultureEconomy economy = CultureEconomy.get(culture);
            for (String key : type.merchantStock().keySet()) {
                CultureEconomy.TradeGood g = economy.good(key);
                if (g == null || g.foreignMerchantPrice() <= 0) {
                    continue;
                }
                int stock = BuildingStock.count(level, building, g.good());
                if (stock > 0) {
                    list.add(new VillageTrade.Offer(true, key, g.good().create(1), g.foreignMerchantPrice(), stock, 0));
                }
            }
        } catch (java.io.IOException e) {
            // 没有类型资料时不卖
        }
        return list;
    }

    /**
     * 打开原版交易界面
     */
    public void open(ServerPlayer player, Component title) {
        rebuildOffers(player);
        setTradingPlayer(player);
        openTradingScreen(player, title, 1);
    }

    void rebuildOffers(Player player) {
        offers = new MerchantOffers();
        sources.clear();
        int reputation = village.getGrowth().getReputation(player.getUUID());
        for (VillageTrade.Offer o : foreign ? foreignOffers() : VillageTrade.offers(level, village, building, player)) {
            if (reputation < o.minReputation()) {
                continue;
            }
            ItemStack[] coins = coins(o.price());
            MerchantOffer offer;
            if (o.villageSells()) {
                int stock = o.stock() < 0 ? UNLIMITED : o.stock();
                offer = new MerchantOffer(coins[0], coins[1], o.item().copy(), 0, stock, 0, 0f, 0);
            } else {
                // 收购只有一个结果格，只能给一种钱币
                offer = new MerchantOffer(o.item().copy(), ItemStack.EMPTY, singleCoin(o.price()), 0, UNLIMITED, 0, 0f, 0);
            }
            offers.add(offer);
            sources.add(o);
        }
    }

    /**
     * 价格 -> 最多两种钱币（1 银 = 64 铜，1 金 = 64 银）
     */
    public static ItemStack[] coins(int price) {
        int gold = price / 4096;
        int silver = (price % 4096) / 64;
        int copper = price % 64;
        if (gold > 0) {
            if (copper > 0) {
                silver++;
                if (silver == 64) {
                    return new ItemStack[] {new ItemStack(ModItems.DENIER_OR.get(), gold + 1), ItemStack.EMPTY};
                }
            }
            return new ItemStack[] {new ItemStack(ModItems.DENIER_OR.get(), gold),
                silver > 0 ? new ItemStack(ModItems.DENIER_ARGENT.get(), silver) : ItemStack.EMPTY};
        }
        if (silver > 0) {
            return new ItemStack[] {new ItemStack(ModItems.DENIER_ARGENT.get(), silver),
                copper > 0 ? new ItemStack(ModItems.DENIER.get(), copper) : ItemStack.EMPTY};
        }
        return new ItemStack[] {new ItemStack(ModItems.DENIER.get(), Math.max(1, copper)), ItemStack.EMPTY};
    }

    /**
     * 收购付款只有一个结果格：64 铜以内给铜币，否则给银币或金币（零头舍去）
     */
    private static ItemStack singleCoin(int price) {
        if (price >= 4096) {
            return new ItemStack(ModItems.DENIER_OR.get(), Math.min(64, price / 4096));
        }
        if (price > 64) {
            return new ItemStack(ModItems.DENIER_ARGENT.get(), price / 64);
        }
        return new ItemStack(ModItems.DENIER.get(), Math.max(1, price));
    }

    // ================ Merchant ================

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        this.tradingPlayer = player;
    }

    @Nullable
    @Override
    public Player getTradingPlayer() {
        return tradingPlayer;
    }

    @Override
    public MerchantOffers getOffers() {
        return offers;
    }

    @Override
    public void overrideOffers(MerchantOffers offers) {
        this.offers = offers;
    }

    /**
     * 原版界面已经把付款和商品在玩家这边换好了；这里处理村庄这边：箱子取货/存货、声望
     */
    @Override
    public void notifyTrade(MerchantOffer offer) {
        int index = offers.indexOf(offer);
        if (index < 0 || index >= sources.size() || tradingPlayer == null) {
            return;
        }
        VillageTrade.Offer source = sources.get(index);
        CultureEconomy.TradeGood good = CultureEconomy.get(com.jasoncian.millenaire_rewrite.buildingplan.VillageTypes.cultureFolder(
            foreign && seller != null ? seller.getCulture() : village.getCulture())).good(source.key());
        if (good == null) {
            return;
        }
        int count = source.item().getCount();
        if (source.villageSells()) {
            if (foreign || !good.autoGenerate()) {
                BuildingStock.take(level, building, good.good(), count);
            }
        } else if (BuildingStock.store(level, building, good.good(), count) < count) {
            // 箱子满了：收购到此为止
            offer.setToOutOfStock();
        }
        village.getGrowth().adjustReputation(tradingPlayer.getUUID(), source.price());
        if (seller != null) {
            seller.playSound(SoundEvents.VILLAGER_YES, 1.0F, seller.getVoicePitch());
        }
    }

    @Override
    public void notifyTradeUpdated(ItemStack stack) {
    }

    @Override
    public int getVillagerXp() {
        return 0;
    }

    @Override
    public void overrideXp(int xp) {
    }

    @Override
    public boolean showProgressBar() {
        return false;
    }

    @Override
    public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }
}

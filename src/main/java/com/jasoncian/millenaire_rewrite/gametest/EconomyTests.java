package com.jasoncian.millenaire_rewrite.gametest;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.economy.CultureEconomy;
import com.jasoncian.millenaire_rewrite.economy.Goods;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 经济数据测试：原版商品、价格表和商店都能换算成 1.20.1 物品。
 */
@GameTestHolder(MillenaireRewrite.MOD_ID)
@PrefixGameTestTemplate(false)
public class EconomyTests {

    private static final String[] CULTURES = {"norman", "byzantines", "indian", "japanese", "mayan", "seljuk", "inuits"};

    /**
     * 建筑库存：面包店的商店是 bakery；存进 10 个面包、取出 3 个后剩 7 个
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void buildingStockStoresAndTakes(GameTestHelper helper) {
        if (TestFilter.skip(helper, "buildingStockStoresAndTakes")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 15200));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "bakery", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);
            var village = new com.jasoncian.millenaire_rewrite.village.Village(origin,
                com.jasoncian.millenaire_rewrite.entity.culture.Culture.NORMAN);
            var bakery = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var bread = Goods.get("bread");
            String shop = village.getGrowth().shopOf(bakery);
            int chests = com.jasoncian.millenaire_rewrite.economy.BuildingStock.chests(level, bakery).size();
            int stored = com.jasoncian.millenaire_rewrite.economy.BuildingStock.store(level, bakery, bread, 10);
            int taken = com.jasoncian.millenaire_rewrite.economy.BuildingStock.take(level, bakery, bread, 3);
            int left = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, bread);
            MillenaireRewrite.LOGGER.info("[gametest] stock: shop {}, {} chests, stored {}, taken {}, left {}", shop, chests,
                stored, taken, left);
            if (!"bakery".equals(shop)) {
                helper.fail("bakery shop is " + shop);
            }
            if (chests == 0 || stored != 10 || taken != 3 || left != 7) {
                helper.fail("stock: " + chests + " chests, stored " + stored + ", taken " + taken + ", left " + left);
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            TestTerrain.setForced(level, centre, false);
        }
        helper.succeed();
    }

    /**
     * 与面包店交易：买面包扣钱、减库存、加声望；卖小麦进箱子、收钱
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void tradeWithBakery(GameTestHelper helper) {
        if (TestFilter.skip(helper, "tradeWithBakery")) {
            return;
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 15800));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "bakery", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);
            var village = new com.jasoncian.millenaire_rewrite.village.Village(origin,
                com.jasoncian.millenaire_rewrite.entity.culture.Culture.NORMAN);
            var bakery = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var bread = Goods.get("bread");
            var wheat = Goods.get("wheat");
            com.jasoncian.millenaire_rewrite.economy.BuildingStock.store(level, bakery, bread, 20);
            var player = helper.makeMockPlayer();
            player.getInventory().clearContent();
            player.getInventory().add(new net.minecraft.world.item.ItemStack(
                com.jasoncian.millenaire_rewrite.core.ModItems.DENIER_ARGENT.get(), 10));
            player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 10));
            var offers = com.jasoncian.millenaire_rewrite.economy.VillageTrade.offers(level, village, bakery, player);
            MillenaireRewrite.LOGGER.info("[gametest] bakery offers: {}", offers.stream()
                .map(o -> (o.villageSells() ? "sells " : "buys ") + o.key() + "@" + o.price() + " stock " + o.stock()).toList());
            var breadOffer = offers.stream().filter(o -> o.villageSells() && o.key().equals("bread")).findFirst().orElse(null);
            var wheatOffer = offers.stream().filter(o -> !o.villageSells() && o.key().equals("wheat")).findFirst().orElse(null);
            if (breadOffer == null || wheatOffer == null) {
                helper.fail("bakery should sell bread and buy wheat: " + offers);
                return;
            }
            int money = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
            var bought = com.jasoncian.millenaire_rewrite.economy.VillageTrade.execute(level, village, bakery, player, true,
                "bread", 5);
            int afterBuy = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
            int breadInChest = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, bread);
            int breadOnPlayer = com.jasoncian.millenaire_rewrite.economy.VillageTrade.countInInventory(player, bread);
            var sold = com.jasoncian.millenaire_rewrite.economy.VillageTrade.execute(level, village, bakery, player, false,
                "wheat", 10);
            int afterSell = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
            int wheatInChest = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, wheat);
            int reputation = village.getGrowth().getReputation(player.getUUID());
            MillenaireRewrite.LOGGER.info("[gametest] bakery trade: bought {} ({}), money {} -> {}, bread chest {} player {}; "
                + "sold {} ({}), money {}, wheat chest {}, reputation {}", bought.count(), bought.problem(), money, afterBuy,
                breadInChest, breadOnPlayer, sold.count(), sold.problem(), afterSell, wheatInChest, reputation);
            if (bought.count() != 5 || afterBuy != money - 5 * breadOffer.price() || breadInChest != 15 || breadOnPlayer != 5) {
                helper.fail("buying bread went wrong");
            }
            if (sold.count() != 10 || afterSell != afterBuy + 10 * wheatOffer.price() || wheatInChest != 10) {
                helper.fail("selling wheat went wrong");
            }
            if (reputation != 5 * breadOffer.price() + 10 * wheatOffer.price()) {
                helper.fail("reputation is " + reputation);
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            TestTerrain.setForced(level, centre, false);
        }
        helper.succeed();
    }

    /**
     * 原版交易界面：价格换成钱币（最多两种）；买面包后箱子少一个，卖小麦后箱子多一个
     */
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void merchantOffersForBakery(GameTestHelper helper) {
        if (TestFilter.skip(helper, "merchantOffersForBakery")) {
            return;
        }
        var c10 = com.jasoncian.millenaire_rewrite.economy.VillageMerchant.coins(10);
        var c70 = com.jasoncian.millenaire_rewrite.economy.VillageMerchant.coins(70);
        var c4166 = com.jasoncian.millenaire_rewrite.economy.VillageMerchant.coins(4096 + 70);
        MillenaireRewrite.LOGGER.info("[gametest] coins 10={} {}, 70={} {}, 4166={} {}", c10[0], c10[1], c70[0], c70[1],
            c4166[0], c4166[1]);
        if (c10[0].getCount() != 10 || c70[0].getCount() != 1 || c70[1].getCount() != 6
            || c4166[0].getCount() != 1 || c4166[1].getCount() != 2) {
            helper.fail("coin conversion is wrong");
        }
        var level = helper.getLevel();
        var centre = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 16400));
        TestTerrain.setForced(level, centre, true);
        TestTerrain.buildPlatform(level, centre);
        try {
            var plan = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlan.get("norman", "bakery", 'A', 0);
            var origin = TestTerrain.ground(centre);
            var result = com.jasoncian.millenaire_rewrite.buildingplan.BuildingPlacer.place(level, plan, origin, 0);
            var village = new com.jasoncian.millenaire_rewrite.village.Village(origin,
                com.jasoncian.millenaire_rewrite.entity.culture.Culture.NORMAN);
            var bakery = village.getGrowth().recordBuilding(plan, origin, 0, result, false);
            var bread = Goods.get("bread");
            var wheat = Goods.get("wheat");
            com.jasoncian.millenaire_rewrite.economy.BuildingStock.store(level, bakery, bread, 20);
            var player = helper.makeMockPlayer();
            var merchant = new com.jasoncian.millenaire_rewrite.economy.VillageMerchant(level, village, bakery, null);
            merchant.setTradingPlayer(player);
            com.jasoncian.millenaire_rewrite.economy.VillageMerchantAccess.rebuild(merchant, player);
            var offers = merchant.getOffers();
            var breadOffer = offers.stream().filter(o -> o.getResult().is(net.minecraft.world.item.Items.BREAD)).findFirst()
                .orElse(null);
            var wheatOffer = offers.stream().filter(o -> o.getBaseCostA().is(net.minecraft.world.item.Items.WHEAT)).findFirst()
                .orElse(null);
            MillenaireRewrite.LOGGER.info("[gametest] merchant offers: {}", offers.stream().map(o -> o.getBaseCostA() + "+"
                + o.getCostB() + "->" + o.getResult() + " max " + o.getMaxUses()).toList());
            if (breadOffer == null || wheatOffer == null) {
                helper.fail("bakery should sell bread and buy wheat");
                return;
            }
            merchant.notifyTrade(breadOffer);
            merchant.notifyTrade(wheatOffer);
            int breadLeft = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, bread);
            int wheatStored = com.jasoncian.millenaire_rewrite.economy.BuildingStock.count(level, bakery, wheat);
            MillenaireRewrite.LOGGER.info("[gametest] merchant: bread max uses {}, bread left {}, wheat stored {}",
                breadOffer.getMaxUses(), breadLeft, wheatStored);
            if (breadOffer.getMaxUses() != 15 || breadLeft != 19 || wheatStored != 1) {
                helper.fail("merchant trade: max uses " + breadOffer.getMaxUses() + ", bread " + breadLeft + ", wheat "
                    + wheatStored);
            }
        } catch (java.io.IOException e) {
            helper.fail(e.toString());
        } finally {
            TestTerrain.setForced(level, centre, false);
        }
        helper.succeed();
    }

    /**
     * 货币（原版比例：1 银 = 64 铜，1 金 = 64 银）：2 银 5 铜 = 133，付 70 剩 63，再收 1 金
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void moneyUsesOriginalRates(GameTestHelper helper) {
        if (TestFilter.skip(helper, "moneyUsesOriginalRates")) {
            return;
        }
        var player = helper.makeMockPlayer();
        player.getInventory().add(new net.minecraft.world.item.ItemStack(
            com.jasoncian.millenaire_rewrite.core.ModItems.DENIER_ARGENT.get(), 2));
        player.getInventory().add(new net.minecraft.world.item.ItemStack(
            com.jasoncian.millenaire_rewrite.core.ModItems.DENIER.get(), 5));
        int start = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
        boolean paid = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.deductCurrencyFromPlayer(player, 70);
        int afterPay = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
        com.jasoncian.millenaire_rewrite.util.CurrencyUtils.giveCurrencyToPlayer(player, 4096);
        int afterGift = com.jasoncian.millenaire_rewrite.util.CurrencyUtils.getPlayerTotalCurrency(player);
        MillenaireRewrite.LOGGER.info("[gametest] money: start {}, paid {}, after paying 70 {}, after 4096 {}", start, paid,
            afterPay, afterGift);
        if (start != 133 || !paid || afterPay != 63 || afterGift != 63 + 4096) {
            helper.fail("money: " + start + " / " + paid + " / " + afterPay + " / " + afterGift);
        }
        helper.succeed();
    }

    /**
     * 每个文化的 traded_goods 和商店引用的商品都能换算成物品
     */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void tradeDataResolves(GameTestHelper helper) {
        if (TestFilter.skip(helper, "tradeDataResolves")) {
            return;
        }
        MillenaireRewrite.LOGGER.info("[gametest] goods: {} loaded, unresolved in itemlist: {}", Goods.all().size(),
            Goods.unresolved());
        List<String> problems = new ArrayList<>();
        for (String culture : CULTURES) {
            CultureEconomy economy = CultureEconomy.get(culture);
            MillenaireRewrite.LOGGER.info("[gametest] economy {}: {} goods, {} shops, problems {}", culture,
                economy.goods().size(), economy.shops().size(), economy.problems());
            if (economy.goods().isEmpty() || economy.shops().isEmpty()) {
                problems.add(culture + ": no trade data");
            }
            economy.problems().forEach(p -> problems.add(culture + ": " + p));
        }
        if (!problems.isEmpty()) {
            helper.fail(problems.size() + " problems: " + String.join("; ", problems.subList(0, Math.min(10, problems.size()))));
        }
        helper.succeed();
    }
}

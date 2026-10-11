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

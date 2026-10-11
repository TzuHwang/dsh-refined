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

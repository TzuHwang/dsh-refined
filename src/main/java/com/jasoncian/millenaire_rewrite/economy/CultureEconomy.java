package com.jasoncian.millenaire_rewrite.economy;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.MillContent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 一个文化的交易数据（原版 Culture.loadGoods / loadShops）：
 * cultures/&lt;文化&gt;/traded_goods.txt 的价格与库存目标，以及 shops/*.txt 的商店买卖清单。
 */
public final class CultureEconomy {

    /**
     * 交易商品。价格以铜币（denier）计：sellingPrice 为村庄卖给玩家的价格，buyingPrice 为村庄向玩家收购的价格；
     * reserved 为商店保留不卖的数量，target 为村庄希望存有的数量
     */
    public record TradeGood(String key, Goods.Good good, int sellingPrice, int buyingPrice, int reserved, int target,
                            int foreignMerchantPrice, boolean autoGenerate, @Nullable String tag, int minReputation,
                            String desc) {
    }

    /**
     * 商店：sells 卖给玩家，buys 向玩家收购，buysOptional 玩家身上有时才收购，deliverTo 村民要送来的物资
     */
    public record Shop(String name, List<TradeGood> sells, List<TradeGood> buys, List<TradeGood> buysOptional,
                       List<Goods.Good> deliverTo) {
    }

    private static final Map<String, CultureEconomy> CACHE = new ConcurrentHashMap<>();

    public final String culture;
    private final Map<String, TradeGood> goods = new LinkedHashMap<>();
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    /** 读取时找不到的商品（用于检查对照表） */
    private final List<String> problems = new ArrayList<>();

    private CultureEconomy(String culture) {
        this.culture = culture;
    }

    /**
     * @param culture 文化目录名（如 norman）
     */
    public static CultureEconomy get(String culture) {
        return CACHE.computeIfAbsent(culture, CultureEconomy::load);
    }

    @Nullable
    public TradeGood good(String key) {
        return goods.get(key.toLowerCase(Locale.ROOT));
    }

    @Nullable
    public TradeGood goodFor(net.minecraft.world.item.ItemStack stack) {
        for (TradeGood g : goods.values()) {
            if (g.good().matches(stack)) {
                return g;
            }
        }
        return null;
    }

    public Map<String, TradeGood> goods() {
        return Collections.unmodifiableMap(goods);
    }

    @Nullable
    public Shop shop(@Nullable String name) {
        return name == null ? null : shops.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Shop> shops() {
        return Collections.unmodifiableMap(shops);
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    // ================ 读取 ================

    private static CultureEconomy load(String culture) {
        CultureEconomy economy = new CultureEconomy(culture);
        Path dir = MillContent.resolve("cultures", culture);
        try {
            economy.loadGoods(dir.resolve("traded_goods.txt"));
            Path shopDir = dir.resolve("shops");
            if (Files.isDirectory(shopDir)) {
                try (Stream<Path> files = Files.list(shopDir)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                        economy.loadShop(file);
                    }
                }
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot read trade data for {}", culture, e);
        }
        MillenaireRewrite.LOGGER.info("Loaded {} traded goods and {} shops for {} ({} problems)", economy.goods.size(),
            economy.shops.size(), culture, economy.problems.size());
        return economy;
    }

    private void loadGoods(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        for (String raw : MillContent.readLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String[] v = line.split(",", -1);
            String key = v[0].trim().toLowerCase(Locale.ROOT);
            Goods.Good good = Goods.get(key);
            if (good == null) {
                if (!Goods.isUnavailable(key)) {
                    problems.add("traded_goods: unknown good " + key);
                }
                continue;
            }
            goods.put(key, new TradeGood(key, good, intAt(v, 1, 0), intAt(v, 2, 0), intAt(v, 3, 0), intAt(v, 4, 0),
                intAt(v, 5, 0), v.length > 6 && Boolean.parseBoolean(v[6].trim()),
                v.length > 7 && !v[7].isBlank() ? v[7].trim() : null, intAt(v, 8, Integer.MIN_VALUE),
                v.length > 9 && !v[9].isBlank() ? v[9].trim() : "foreigntrade"));
        }
    }

    private void loadShop(Path file) throws IOException {
        String name = file.getFileName().toString();
        name = name.substring(0, name.length() - ".txt".length()).toLowerCase(Locale.ROOT);
        List<TradeGood> sells = new ArrayList<>();
        List<TradeGood> buys = new ArrayList<>();
        List<TradeGood> optional = new ArrayList<>();
        List<Goods.Good> deliver = new ArrayList<>();
        for (String raw : MillContent.readLines(file)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("//") || eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            for (String item : line.substring(eq + 1).toLowerCase(Locale.ROOT).split(",")) {
                item = item.trim();
                if (item.isEmpty()) {
                    continue;
                }
                if (key.equals("deliverto")) {
                    Goods.Good good = Goods.get(item);
                    if (good == null) {
                        if (!Goods.isUnavailable(item)) {
                            problems.add("shop " + name + ": unknown delivered good " + item);
                        }
                    } else {
                        deliver.add(good);
                    }
                    continue;
                }
                List<TradeGood> target = switch (key) {
                    case "sells" -> sells;
                    case "buys" -> buys;
                    case "buysoptional" -> optional;
                    default -> null;
                };
                if (target == null) {
                    problems.add("shop " + name + ": unknown parameter " + key);
                    break;
                }
                TradeGood good = goods.get(item);
                if (good == null) {
                    if (!Goods.isUnavailable(item)) {
                        problems.add("shop " + name + ": unknown traded good " + item);
                    }
                } else {
                    target.add(good);
                }
            }
        }
        shops.put(name, new Shop(name, List.copyOf(sells), List.copyOf(buys), List.copyOf(optional), List.copyOf(deliver)));
    }

    private static int intAt(String[] v, int i, int fallback) {
        if (v.length <= i || v[i].isBlank()) {
            return fallback;
        }
        try {
            return readInteger(v[i]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 原版 MillCommonUtilities.readInteger：数字可以写成乘式，如 2*64*64
     */
    public static int readInteger(String text) {
        int result = 1;
        for (String part : text.trim().split("\\*")) {
            result *= Integer.parseInt(part.trim());
        }
        return result;
    }
}

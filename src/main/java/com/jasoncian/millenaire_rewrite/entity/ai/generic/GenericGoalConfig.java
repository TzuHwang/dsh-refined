package com.jasoncian.millenaire_rewrite.entity.ai.generic;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import com.jasoncian.millenaire_rewrite.buildingplan.MillContent;
import com.jasoncian.millenaire_rewrite.economy.Goods;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 原版 Millénaire 的数据驱动目标（millenaire/goals/generic&lt;类型&gt;/**.txt，原版 GoalGeneric 及其子类的参数）。
 * 目标键为文件名（小写，不含 .txt）；类型由所在的顶层目录决定（genericcrafting -> crafting）。
 */
public final class GenericGoalConfig {

    public final String key;
    public final String type;
    /** 原始参数（小写键 -> 值列表，同一个键可以写多行） */
    private final Map<String, List<String>> params;

    // ---- 共同参数（原版 Goal / GoalGeneric）----
    public final int priority;
    public final int priorityRandom;
    /** 到达后执行动作前的时间（tick；原版以毫秒写，5000 毫秒 = 100 tick） */
    public final int duration;
    public final double range;
    public final boolean leisure;
    public final boolean sprint;
    public final boolean townHallGoal;
    @Nullable
    public final String buildingTag;
    @Nullable
    public final String requiredTag;
    public final int maxSimultaneousInBuilding;
    public final int maxSimultaneousTotal;
    /** 两次之间至少间隔的时间（tick） */
    public final int reoccurDelay;
    public final int minimumHour;
    public final int maximumHour;
    public final List<Goods.Good> heldItems;
    public final List<Goods.Good> heldItemsDestination;
    public final List<Goods.Good> heldItemsOffHand;
    public final List<Goods.Good> heldItemsOffHandDestination;
    /** 建筑里某物品超过这个数量时不做 */
    public final Map<Goods.Good, Integer> buildingLimit;
    public final Map<Goods.Good, Integer> townHallLimit;
    public final Map<Goods.Good, Integer> villageLimit;
    /** 市政厅里第二种比第一种多时不做 */
    public final List<Goods.Good> itemsBalance;
    public final List<String> goalTags;
    /** 引用了无法换算的商品（这个目标无法正确执行） */
    public final List<String> problems = new ArrayList<>();

    private GenericGoalConfig(String key, String type, Map<String, List<String>> params) {
        this.key = key;
        this.type = type;
        this.params = params;
        this.priority = intParam("priority", 50);
        this.priorityRandom = intParam("priorityrandom", 10);
        this.duration = msParam("duration", 5000);
        this.range = intParam("range", 3);
        this.leisure = boolParam("leasure");
        this.sprint = boolParam("sprint");
        this.townHallGoal = boolParam("townhallgoal");
        this.buildingTag = string("buildingtag");
        this.requiredTag = string("requiredtag");
        this.maxSimultaneousInBuilding = intParam("maxsimultaneousinbuilding", 0);
        this.maxSimultaneousTotal = intParam("maxsimultaneoustotal", 0);
        this.reoccurDelay = msParam("reoccurdelay", 0);
        this.minimumHour = intParam("minimumhour", -1);
        this.maximumHour = intParam("maximumhour", -1);
        this.heldItems = goodsList("helditems");
        this.heldItemsDestination = goodsList("helditemsdestination");
        this.heldItemsOffHand = goodsList("helditemsoffhand");
        this.heldItemsOffHandDestination = goodsList("helditemsoffhanddestination");
        this.buildingLimit = goodsAmounts("buildinglimit");
        this.townHallLimit = goodsAmounts("townhalllimit");
        this.villageLimit = goodsAmounts("villagelimit");
        this.itemsBalance = goodsList("itemsbalance");
        List<String> tags = new ArrayList<>();
        all("tag").forEach(t -> tags.add(t.toLowerCase(Locale.ROOT)));
        this.goalTags = List.copyOf(tags);
    }

    // ================ 参数读取 ================

    public List<String> all(String name) {
        return params.getOrDefault(name, List.of());
    }

    @Nullable
    public String string(String name) {
        List<String> values = all(name);
        return values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    public int intParam(String name, int fallback) {
        String v = string(name);
        if (v == null) {
            return fallback;
        }
        try {
            return com.jasoncian.millenaire_rewrite.economy.CultureEconomy.readInteger(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 原版 MILLISECONDS 参数：换算成 tick */
    public int msParam(String name, int fallbackMs) {
        return intParam(name, fallbackMs) / 50;
    }

    public boolean boolParam(String name) {
        return Boolean.parseBoolean(string(name));
    }

    @Nullable
    public Goods.Good good(String name) {
        String v = string(name);
        return v == null ? null : resolve(v.split(",")[0].trim());
    }

    /** 逗号分隔（可多行）的商品列表 */
    public List<Goods.Good> goodsList(String name) {
        List<Goods.Good> list = new ArrayList<>();
        for (String line : all(name)) {
            for (String part : line.split(",")) {
                Goods.Good g = resolve(part.trim());
                if (g != null) {
                    list.add(g);
                }
            }
        }
        return List.copyOf(list);
    }

    /** 每行 "商品,数量"（原版 INVITEM_NUMBER_ADD） */
    public Map<Goods.Good, Integer> goodsAmounts(String name) {
        Map<Goods.Good, Integer> map = new LinkedHashMap<>();
        for (String line : all(name)) {
            String[] p = line.split(",");
            if (p.length < 2) {
                continue;
            }
            Goods.Good g = resolve(p[0].trim());
            if (g != null) {
                try {
                    map.merge(g, com.jasoncian.millenaire_rewrite.economy.CultureEconomy.readInteger(p[1]), Integer::sum);
                } catch (NumberFormatException e) {
                    problems.add(name + ": bad number in " + line);
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    @Nullable
    private Goods.Good resolve(String key) {
        if (key.isEmpty()) {
            return null;
        }
        Goods.Good g = Goods.get(key);
        if (g == null && !Goods.isUnavailable(key)) {
            problems.add("unknown good " + key);
        }
        return g;
    }

    // ================ 注册表 ================

    private static Map<String, GenericGoalConfig> configs;

    @Nullable
    public static GenericGoalConfig get(String key) {
        return all().get(key.toLowerCase(Locale.ROOT));
    }

    public static synchronized Map<String, GenericGoalConfig> all() {
        if (configs == null) {
            configs = load();
        }
        return configs;
    }

    private static Map<String, GenericGoalConfig> load() {
        Map<String, GenericGoalConfig> map = new HashMap<>();
        Path root = MillContent.resolve("goals");
        if (!Files.isDirectory(root)) {
            return map;
        }
        try (Stream<Path> typeDirs = Files.list(root)) {
            for (Path typeDir : typeDirs.filter(Files::isDirectory).toList()) {
                String dirName = typeDir.getFileName().toString().toLowerCase(Locale.ROOT);
                String type = dirName.startsWith("generic") ? dirName.substring("generic".length()) : dirName;
                try (Stream<Path> files = Files.walk(typeDir)) {
                    List<Path> found = files.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".txt")).toList();
                    for (Path file : found) {
                        String name = file.getFileName().toString();
                        String key = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                        try {
                            map.put(key, new GenericGoalConfig(key, type, read(file)));
                        } catch (IOException e) {
                            MillenaireRewrite.LOGGER.warn("Cannot read generic goal {}: {}", file, e.toString());
                        }
                    }
                }
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot read generic goals", e);
        }
        MillenaireRewrite.LOGGER.info("Loaded {} generic goals", map.size());
        return map;
    }

    private static Map<String, List<String>> read(Path file) throws IOException {
        Map<String, List<String>> params = new HashMap<>();
        // 原版有些文件不是 UTF-8（法文注释用 Latin-1），读不了时改用 Latin-1
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("//") || eq < 0) {
                continue;
            }
            params.computeIfAbsent(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                .add(line.substring(eq + 1).trim());
        }
        return params;
    }
}

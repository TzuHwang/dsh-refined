package com.jasoncian.millenaire_rewrite.content;

import com.jasoncian.millenaire_rewrite.buildingplan.MillContent;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取原版 cultures/&lt;文化&gt;/villagers/**&#47;&lt;类型&gt;.txt 村民类型定义和 namelists 名字列表。
 */
public final class VillagerTypes {

    /**
     * @param key        类型键名（文件名），如 seneschal
     * @param nativeName 本地名称，如 Sénéchal
     * @param female     是否为女性
     * @param textures   皮肤材质（相对于 assets/millenaire_rewrite/），随机选一个
     * @param clothes    衣服层 -> 可选材质（只取默认的 "free" 衣服）
     */
    public record VillagerType(String culture, String key, String nativeName, boolean female,
                               @Nullable String firstNameList, @Nullable String familyNameList,
                               List<String> textures, Map<Integer, List<String>> clothes,
                               float baseHeight, int health, List<String> tags, String model,
                               List<String> goals, Map<String, Integer> requiredGoods, List<String> collectGoods,
                               List<String> bringBackHomeGoods, @Nullable String defaultWeapon, int baseAttackStrength,
                               @Nullable String maleChild, @Nullable String femaleChild,
                               Map<String, Integer> merchantStock, int chanceWeight) {

        /** 原版 tag=visitor：访客，住几晚后离开 */
        public boolean isVisitor() {
            return tags.stream().anyMatch(t -> t.equalsIgnoreCase("visitor"));
        }

        /** 原版 tag=foreignmerchant：在市集摆摊的外地商人 */
        public boolean isForeignMerchant() {
            return tags.stream().anyMatch(t -> t.equalsIgnoreCase("foreignmerchant"));
        }

        /** 原版 tag=child */
        public boolean isChild() {
            return tags.stream().anyMatch(t -> t.equalsIgnoreCase("child"));
        }

        /** 原版 tag=helpInAttacks：村里有人被攻击时会来帮忙 */
        public boolean helpsInAttacks() {
            return tags.stream().anyMatch(t -> t.equalsIgnoreCase("helpInAttacks"));
        }

        public String randomTexture(RandomSource random) {
            return textures.isEmpty() ? "" : textures.get(random.nextInt(textures.size()));
        }

        public String randomClothes(int layer, RandomSource random) {
            List<String> options = clothes.get(layer);
            return options == null || options.isEmpty() ? "" : options.get(random.nextInt(options.size()));
        }
    }

    private static final Map<String, VillagerType> TYPES = new HashMap<>();
    private static final Map<String, List<String>> NAME_LISTS = new HashMap<>();

    private VillagerTypes() {
    }

    /**
     * 文化的所有村民类型
     */
    public static synchronized List<VillagerType> all(String culture) throws IOException {
        List<VillagerType> list = new ArrayList<>();
        java.nio.file.Path dir = MillContent.resolve("cultures", culture, "villagers");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return list;
        }
        try (var files = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path f : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                String name = f.getFileName().toString();
                try {
                    list.add(get(culture, name.substring(0, name.length() - 4)));
                } catch (IOException | RuntimeException e) {
                    // 跳过读不了的类型
                }
            }
        }
        return list;
    }

    public static synchronized VillagerType get(String culture, String key) throws IOException {
        String cacheKey = culture + "/" + key;
        VillagerType type = TYPES.get(cacheKey);
        if (type == null) {
            type = load(culture, key);
            TYPES.put(cacheKey, type);
        }
        return type;
    }

    /**
     * 从名字列表中随机取一个名字；列表不存在时返回空字符串
     */
    public static synchronized String randomName(String culture, @Nullable String list, RandomSource random) {
        if (list == null) {
            return "";
        }
        List<String> names = NAME_LISTS.computeIfAbsent(culture + "/" + list, k -> {
            try {
                Path file = MillContent.resolve("cultures", culture, "namelists", list + ".txt");
                if (!Files.exists(file)) {
                    return List.of();
                }
                return MillContent.readLines(file).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("//"))
                    .toList();
            } catch (IOException e) {
                return List.of();
            }
        });
        return names.isEmpty() ? "" : names.get(random.nextInt(names.size()));
    }

    private static VillagerType load(String culture, String key) throws IOException {
        Path file = MillContent.find(MillContent.resolve("cultures", culture, "villagers"), key + ".txt");
        if (file == null) {
            throw new IOException("Villager type not found: " + culture + "/" + key);
        }
        String nativeName = key;
        boolean female = false;
        String firstNames = null;
        String familyNames = null;
        float baseHeight = 1.0F;
        int health = 20;
        List<String> textures = new ArrayList<>();
        Map<Integer, List<String>> clothes = new HashMap<>();
        List<String> tags = new ArrayList<>();
        String model = "";
        List<String> goals = new ArrayList<>();
        Map<String, Integer> requiredGoods = new java.util.LinkedHashMap<>();
        List<String> collectGoods = new ArrayList<>();
        List<String> bringBackHomeGoods = new ArrayList<>();
        String defaultWeapon = null;
        String maleChild = null;
        String femaleChild = null;
        Map<String, Integer> merchantStock = new java.util.LinkedHashMap<>();
        int chanceWeight = 0;
        int baseAttackStrength = 1;

        for (String raw : MillContent.readLines(file)) {
            String line = raw.trim();
            if (line.startsWith("//") || !line.contains("=")) {
                continue;
            }
            String[] kv = line.split("=", 2);
            String v = kv[1].trim();
            switch (kv[0].trim().toLowerCase()) {
                case "native_name" -> nativeName = v;
                case "gender" -> female = v.equalsIgnoreCase("female");
                case "firstnamelist" -> firstNames = v;
                case "familynamelist" -> familyNames = v;
                case "baseheight" -> baseHeight = Float.parseFloat(v);
                case "health" -> health = Integer.parseInt(v);
                case "texture" -> textures.add(v.toLowerCase());
                case "tag" -> tags.add(v);
                case "model" -> model = v;
                case "goal" -> goals.add(v.toLowerCase());
                case "requiredgood" -> {
                    // requiredGood=商品,数量：家里应该常备的物资（原版 requiredGoods）
                    String[] p = v.split(",");
                    if (p.length == 2) {
                        try {
                            requiredGoods.merge(p[0].trim().toLowerCase(),
                                com.jasoncian.millenaire_rewrite.economy.CultureEconomy.readInteger(p[1]), Integer::sum);
                        } catch (NumberFormatException e) {
                            // 忽略写错的行
                        }
                    }
                }
                case "collectgood" -> collectGoods.add(v.toLowerCase());
                case "defaultweapon" -> defaultWeapon = v.toLowerCase();
                case "malechild" -> maleChild = v.toLowerCase();
                case "merchantstock" -> {
                    String[] p = v.split(",");
                    if (p.length == 2) {
                        try {
                            merchantStock.merge(p[0].trim().toLowerCase(),
                                com.jasoncian.millenaire_rewrite.economy.CultureEconomy.readInteger(p[1]), Integer::sum);
                        } catch (NumberFormatException e) {
                            // 忽略写错的行
                        }
                    }
                }
                case "chanceweight" -> {
                    try {
                        chanceWeight = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
                case "femalechild" -> femaleChild = v.toLowerCase();
                case "baseattackstrength" -> {
                    try {
                        baseAttackStrength = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
                case "bringbackhomegood" -> bringBackHomeGoods.add(v.toLowerCase());
                case "clothes" -> {
                    // clothes=名称,材质 或 clothes=名称,层,材质；只使用默认衣服 "free"
                    String[] parts = v.split(",");
                    if (parts[0].equals("free")) {
                        int layer = parts.length >= 3 ? Integer.parseInt(parts[1]) : 0;
                        clothes.computeIfAbsent(layer, k -> new ArrayList<>()).add(parts[parts.length - 1].toLowerCase());
                    }
                }
                default -> {
                }
            }
        }
        return new VillagerType(culture, key, nativeName, female, firstNames, familyNames,
            List.copyOf(textures), clothes, baseHeight, health, List.copyOf(tags), model, List.copyOf(goals),
            Map.copyOf(requiredGoods), List.copyOf(collectGoods), List.copyOf(bringBackHomeGoods), defaultWeapon,
            baseAttackStrength, maleChild, femaleChild, Map.copyOf(merchantStock), chanceWeight);
    }
}

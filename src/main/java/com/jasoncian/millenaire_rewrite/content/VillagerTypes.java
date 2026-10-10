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
                               List<String> goals) {

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
            List.copyOf(textures), clothes, baseHeight, health, List.copyOf(tags), model, List.copyOf(goals));
    }
}

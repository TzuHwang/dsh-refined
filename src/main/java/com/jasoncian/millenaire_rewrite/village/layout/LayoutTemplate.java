package com.jasoncian.millenaire_rewrite.village.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 村庄佈局模板：广场、道路骨架和道路两侧的临街带（millenaire_layouts/&lt;文化&gt;/*.json）。
 *
 * 模板坐标：x 向东、z 向南，广场中心为 (0,0)。市政厅位于广场北侧、正面朝南。
 * 道路只能是东西或南北向的直线段。临街带沿某条道路的一侧，建筑要建造时才在带上找一段空位，
 * 正面朝向道路，所以同一个模板能容纳各种尺寸的建筑。
 */
public final class LayoutTemplate {

    /** 道路：from -> to 的直线段，宽度为奇数 */
    public record Road(int fromX, int fromZ, int toX, int toZ, int width) {
        public int length() {
            return Math.abs(toX - fromX) + Math.abs(toZ - fromZ);
        }

        public int dirX() {
            return Integer.signum(toX - fromX);
        }

        public int dirZ() {
            return Integer.signum(toZ - fromZ);
        }
    }

    /**
     * 临街带：道路 road 的一侧（沿 from->to 方向的 left/right），距道路起点 start..end 的一段，
     * 建筑进深不超过 depth；tag 为可放的建筑类别（house / plaza / field）
     */
    public record Frontage(int road, boolean right, int start, int end, int depth, String tag) {
    }

    public final String culture;
    public final String name;
    public final int weight;
    /** 广场半宽：以 (0,0) 为中心的正方形 */
    public final int plaza;
    public final List<Road> roads;
    public final List<Frontage> frontages;

    private LayoutTemplate(String culture, String name, int weight, int plaza, List<Road> roads, List<Frontage> frontages) {
        this.culture = culture;
        this.name = name;
        this.weight = weight;
        this.plaza = plaza;
        this.roads = List.copyOf(roads);
        this.frontages = List.copyOf(frontages);
    }

    // ================ 读取 ================

    private static final Map<String, List<LayoutTemplate>> TEMPLATES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> CATEGORIES = new ConcurrentHashMap<>();

    @Nullable
    private static Path root() {
        var file = ModList.get().getModFileById(MillenaireRewrite.MOD_ID);
        return file == null ? null : file.getFile().findResource("millenaire_layouts");
    }

    /**
     * 文化的所有模板（文化目录名，如 norman）；没有模板的文化返回空列表
     */
    public static List<LayoutTemplate> forCulture(String culture) {
        return TEMPLATES.computeIfAbsent(culture, LayoutTemplate::loadAll);
    }

    @Nullable
    public static LayoutTemplate get(String culture, String name) {
        return forCulture(culture).stream().filter(t -> t.name.equals(name)).findFirst().orElse(null);
    }

    /**
     * 建筑类别（categories.json 中列出的建筑为 plaza / field 等，其余为 house）
     */
    public static String category(String culture, String buildingKey) {
        return CATEGORIES.computeIfAbsent(culture, LayoutTemplate::loadCategories).getOrDefault(buildingKey, "house");
    }

    private static List<LayoutTemplate> loadAll(String culture) {
        List<LayoutTemplate> list = new ArrayList<>();
        Path root = root();
        if (root == null) {
            return list;
        }
        Path dir = root.resolve(culture);
        if (!Files.isDirectory(dir)) {
            return list;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")
                && !p.getFileName().toString().equals("categories.json")).sorted().toList()) {
                try {
                    list.add(parse(culture, file));
                } catch (Exception e) {
                    MillenaireRewrite.LOGGER.error("Cannot read village layout {}", file, e);
                }
            }
        } catch (IOException e) {
            MillenaireRewrite.LOGGER.error("Cannot list village layouts for {}", culture, e);
        }
        MillenaireRewrite.LOGGER.info("Loaded {} village layouts for {}", list.size(), culture);
        return list;
    }

    private static LayoutTemplate parse(String culture, Path file) throws IOException {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".json".length());
        List<Road> roads = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("roads")) {
            JsonObject r = e.getAsJsonObject();
            JsonArray from = r.getAsJsonArray("from");
            JsonArray to = r.getAsJsonArray("to");
            Road road = new Road(from.get(0).getAsInt(), from.get(1).getAsInt(), to.get(0).getAsInt(), to.get(1).getAsInt(),
                r.has("width") ? r.get("width").getAsInt() : 3);
            if (road.fromX() != road.toX() && road.fromZ() != road.toZ()) {
                throw new IOException(fileName + ": roads must be straight east-west or north-south lines");
            }
            roads.add(road);
        }
        List<Frontage> frontages = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("frontages")) {
            JsonObject f = e.getAsJsonObject();
            int road = f.get("road").getAsInt();
            if (road < 0 || road >= roads.size()) {
                throw new IOException(fileName + ": frontage refers to missing road " + road);
            }
            String side = f.get("side").getAsString();
            int start = f.has("start") ? f.get("start").getAsInt() : 0;
            int end = f.has("end") ? f.get("end").getAsInt() : roads.get(road).length();
            int depth = f.get("depth").getAsInt();
            String tag = f.has("tag") ? f.get("tag").getAsString() : "house";
            if (side.equals("left") || side.equals("both")) {
                frontages.add(new Frontage(road, false, start, end, depth, tag));
            }
            if (side.equals("right") || side.equals("both")) {
                frontages.add(new Frontage(road, true, start, end, depth, tag));
            }
        }
        return new LayoutTemplate(culture, name, json.has("weight") ? json.get("weight").getAsInt() : 10,
            json.has("plaza") ? json.get("plaza").getAsInt() : 6, roads, frontages);
    }

    private static Map<String, String> loadCategories(String culture) {
        Map<String, String> map = new HashMap<>();
        Path root = root();
        Path file = root == null ? null : root.resolve(culture).resolve("categories.json");
        if (file == null || !Files.exists(file)) {
            return map;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (String category : json.keySet()) {
                for (JsonElement key : json.getAsJsonArray(category)) {
                    map.put(key.getAsString(), category);
                }
            }
        } catch (Exception e) {
            MillenaireRewrite.LOGGER.error("Cannot read building categories for {}", culture, e);
        }
        return map;
    }

    @Override
    public String toString() {
        return culture + "/" + name;
    }
}

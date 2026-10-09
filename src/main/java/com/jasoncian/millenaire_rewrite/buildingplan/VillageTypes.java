package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.entity.culture.Culture;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 读取 cultures/&lt;文化&gt;/villages/*.txt 中的村庄类型。
 */
public final class VillageTypes {

    /**
     * @param fileName   村庄类型文件名（不含扩展名），如 agricole
     * @param name       显示名称
     * @param centre     中心建筑（市政厅）的键名，如 manor
     * @param weight     随机选择权重
     * @param start      村庄生成时一起建好的建筑
     * @param core       优先建造的建筑
     * @param secondary  核心建筑之后建造的建筑
     */
    public record VillageType(String fileName, String name, String centre, int weight,
                              List<String> start, List<String> core, List<String> secondary) {
    }

    /**
     * 按文件名读取村庄类型（用于从存档恢复）
     */
    @Nullable
    public static VillageType get(Culture culture, String fileName) throws IOException {
        Path file = MillContent.resolve("cultures", cultureFolder(culture), "villages", fileName + ".txt");
        return Files.exists(file) ? read(file) : null;
    }

    private VillageTypes() {
    }

    /**
     * 本模组 Culture 枚举 -> 原版内容中的文化目录名
     */
    public static String cultureFolder(Culture culture) {
        return switch (culture) {
            case BYZANTINE -> "byzantines";
            case INUIT -> "inuits";
            default -> culture.getId();
        };
    }

    /**
     * 列出可以自然生成的村庄类型（排除玩家控制的村庄）
     */
    public static List<VillageType> list(Culture culture) throws IOException {
        Path dir = MillContent.resolve("cultures", cultureFolder(culture), "villages");
        List<VillageType> types = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return types;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                VillageType type = read(file);
                if (type != null && !type.centre().startsWith("player") && !type.fileName().contains("controlled")) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    @Nullable
    public static VillageType pick(Culture culture, RandomSource random) throws IOException {
        List<VillageType> types = list(culture);
        int total = types.stream().mapToInt(VillageType::weight).sum();
        if (total <= 0) {
            return types.isEmpty() ? null : types.get(random.nextInt(types.size()));
        }
        int roll = random.nextInt(total);
        for (VillageType type : types) {
            roll -= type.weight();
            if (roll < 0) {
                return type;
            }
        }
        return types.get(types.size() - 1);
    }

    @Nullable
    private static VillageType read(Path file) throws IOException {
        String fileName = file.getFileName().toString().replace(".txt", "");
        String name = fileName;
        String centre = null;
        int weight = 1;
        List<String> start = new ArrayList<>();
        List<String> core = new ArrayList<>();
        List<String> secondary = new ArrayList<>();
        for (String raw : MillContent.readLines(file)) {
            String line = raw.trim();
            if (line.startsWith("//") || !line.contains("=")) {
                continue;
            }
            String[] kv = line.split("=", 2);
            switch (kv[0].trim().toLowerCase()) {
                case "name" -> name = kv[1].trim();
                case "centre" -> centre = kv[1].trim();
                case "weight" -> weight = Integer.parseInt(kv[1].trim());
                case "start" -> start.add(kv[1].trim());
                case "core" -> core.add(kv[1].trim());
                case "secondary" -> secondary.add(kv[1].trim());
                default -> {
                }
            }
        }
        return centre == null ? null
            : new VillageType(fileName, name, centre, weight, List.copyOf(start), List.copyOf(core), List.copyOf(secondary));
    }
}

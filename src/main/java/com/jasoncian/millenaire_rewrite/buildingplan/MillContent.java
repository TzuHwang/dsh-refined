package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 访问模组 jar 内 millenaire/ 目录下的原版 Millénaire 内容文件（文化、建筑图纸等）。
 *
 * 这些文件名包含大写字母，不能作为 ResourceLocation 使用，所以直接从模组文件读取，
 * 开发环境和打包后的 jar 都适用。
 */
public final class MillContent {

    private static Path root;

    private MillContent() {
    }

    public static Path root() {
        if (root == null) {
            root = ModList.get().getModFileById(MillenaireRewrite.MOD_ID).getFile().findResource("millenaire");
        }
        return root;
    }

    public static Path resolve(String first, String... more) {
        Path p = root().resolve(first);
        for (String part : more) {
            p = p.resolve(part);
        }
        return p;
    }

    public static List<String> readLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    /**
     * 在目录树中查找指定文件名（区分大小写）
     */
    public static Path find(Path dir, String fileName) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().equals(fileName)).findFirst().orElse(null);
        }
    }
}

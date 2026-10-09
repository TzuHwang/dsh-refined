package com.jasoncian.millenaire_rewrite.buildingplan;

import com.jasoncian.millenaire_rewrite.MillenaireRewrite;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一栋建筑某个升级等级的图纸。
 *
 * 图纸由 &lt;key&gt;_&lt;variation&gt;.txt（参数）和 &lt;key&gt;_&lt;variation&gt;&lt;level&gt;.png（方块）组成。
 * PNG 中每一层楼横向排列，层与层之间隔一列像素；每个像素颜色对应 blocklist.txt 的一项。
 * 对应原版 org.millenaire.common.buildingplan.PngPlanLoader。
 */
public final class BuildingPlan {

    public final String culture;
    public final String key;
    public final char variation;
    public final int level;

    public int length;
    public int width;
    public int buildingOrientation = 1;
    public int startLevel;
    public String nativeName;
    public final List<String> maleResidents = new ArrayList<>();
    public final List<String> femaleResidents = new ArrayList<>();

    public int nbFloors;
    /** [楼层][长度方向][宽度方向]，null 表示未知颜色（按空处理） */
    public PointType[][][] plan;

    private BuildingPlan(String culture, String key, char variation, int level) {
        this.culture = culture;
        this.key = key;
        this.variation = variation;
        this.level = level;
    }

    public int lengthOffset() {
        return length / 2;
    }

    public int widthOffset() {
        return width / 2;
    }

    /**
     * 读取图纸。
     *
     * @param culture   文化目录名（如 norman）
     * @param key       建筑键名（如 manor）
     * @param variation 变体字母（通常为 'A'）
     * @param level     升级等级，0 为初始建筑
     */
    public static BuildingPlan load(String culture, String key, char variation, int level) throws IOException {
        Path buildingsDir = MillContent.resolve("cultures", culture, "buildings");
        String base = key + "_" + variation;
        Path txt = MillContent.find(buildingsDir, base + ".txt");
        if (txt == null) {
            throw new IOException("Building config not found: " + culture + "/" + base + ".txt");
        }
        Path png = txt.resolveSibling(base + level + ".png");
        if (!Files.exists(png)) {
            throw new IOException("Building plan image not found: " + png);
        }

        BuildingPlan plan = new BuildingPlan(culture, key, variation, level);
        plan.readConfig(txt);
        plan.readImage(png);
        return plan;
    }

    private void readConfig(Path txt) throws IOException {
        String upgradePrefix = "upgrade" + level + ".";
        Integer upgradeStartLevel = null;
        for (String raw : MillContent.readLines(txt)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//") || !line.contains("=")) {
                continue;
            }
            String[] kv = line.split("=", 2);
            String k = kv[0].trim().toLowerCase();
            String v = kv[1].trim();
            switch (k) {
                case "building.length" -> length = Integer.parseInt(v);
                case "building.width" -> width = Integer.parseInt(v);
                case "building.buildingorientation" -> buildingOrientation = Integer.parseInt(v);
                case "initial.startlevel" -> startLevel = Integer.parseInt(v);
                case "initial.nativename" -> nativeName = v;
                case "initial.male" -> maleResidents.add(v);
                case "initial.female" -> femaleResidents.add(v);
                default -> {
                    if (k.equals(upgradePrefix + "startlevel")) {
                        upgradeStartLevel = Integer.parseInt(v);
                    }
                }
            }
        }
        if (level > 0 && upgradeStartLevel != null) {
            startLevel = upgradeStartLevel;
        }
        if (length <= 0 || width <= 0) {
            throw new IOException(txt + ": missing building.length/building.width");
        }
    }

    private void readImage(Path png) throws IOException {
        BufferedImage image;
        try (InputStream in = Files.newInputStream(png)) {
            image = ImageIO.read(in);
        }
        if (image == null) {
            throw new IOException("Cannot decode " + png);
        }
        if (image.getHeight() != length) {
            throw new IOException(png + ": expected height " + length + " but image is " + image.getHeight());
        }
        float floors = (image.getWidth() + 1.0F) / (width + 1.0F);
        if (Math.round(floors) != floors) {
            throw new IOException(png + ": width " + image.getWidth() + " does not fit floors of width " + width);
        }
        nbFloors = (int) floors;
        plan = new PointType[nbFloors][length][width];

        Map<Integer, PointType> colours = BlockList.get();
        boolean hasAlpha = image.getColorModel().hasAlpha();
        int unknown = 0;
        for (int floor = 0; floor < nbFloors; floor++) {
            for (int l = 0; l < length; l++) {
                for (int w = 0; w < width; w++) {
                    int px = floor * (width + 1) + (width - w - 1);
                    int argb = image.getRGB(px, l);
                    int colour = hasAlpha && (argb >>> 24) != 0xFF ? BlockList.EMPTY_COLOUR : argb & 0xFFFFFF;
                    PointType pt = colours.get(colour);
                    if (pt == null) {
                        unknown++;
                        pt = colours.get(BlockList.EMPTY_COLOUR);
                    }
                    plan[floor][l][w] = pt;
                }
            }
        }
        if (unknown > 0) {
            MillenaireRewrite.LOGGER.warn("{}: {} pixels with unknown colours treated as empty", png.getFileName(), unknown);
        }
    }

    @Override
    public String toString() {
        return culture + "/" + key + "_" + variation + level + " (" + length + "x" + width + "x" + nbFloors + ")";
    }
}

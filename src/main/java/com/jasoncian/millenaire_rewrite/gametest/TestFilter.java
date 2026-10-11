package com.jasoncian.millenaire_rewrite.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 只运行选定的测试：系统属性 millenaire.tests（来自环境变量 MILL_TESTS）为逗号分隔的名称片段，
 * 测试名称包含其中任一片段（不分大小写）才运行，其余测试直接通过。为空时运行全部测试。
 */
public final class TestFilter {

    private static final List<String> SELECTED = Arrays.stream(System.getProperty("millenaire.tests", "").split(","))
        .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();

    private TestFilter() {
    }

    /**
     * @return true 表示这个测试没被选中，已直接标记通过，调用方应立即返回
     */
    public static boolean skip(GameTestHelper helper, String name) {
        if (SELECTED.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (SELECTED.stream().anyMatch(lower::contains)) {
            return false;
        }
        helper.succeed();
        return true;
    }
}

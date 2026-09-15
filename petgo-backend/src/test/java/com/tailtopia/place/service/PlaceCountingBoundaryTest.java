package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0（扫源码，无 Spring/DB）：计数的**两条架构边界**
 * （V1.3.0 batch-b1 Story 1.8 · AC2 / AC4 · AD-9）。
 *
 * <h2>为什么这两条要用机械检查</h2>
 * 两条都是**越界了功能反而更"好"**的那种约束：
 * <ul>
 *   <li><b>AC2</b>：给列表整页结果加一层缓存会让接口立刻变快，没有任何测试会红 ——
 *       但那是基线明令禁止的「通用缓存层」，AD-9 授权的只有「单个整数计数器 + 可从库重算」。</li>
 *   <li><b>AC4</b>：拿推荐数去给列表排序 / 给差评场所加个警示标，看起来像是产品升级 ——
 *       而 PRD ③ 明确「本版只展示、不参与排序、不做降权、不做警示标，先积累数据」。</li>
 * </ul>
 */
class PlaceCountingBoundaryTest {

    private static final Path SRC = Path.of("src/main/java/com/tailtopia/place");

    private static String read(String relative) {
        try {
            return Files.readString(SRC.resolve(relative));
        } catch (IOException e) {
            throw new IllegalStateException(relative + " 不在了（改名了就改这条测试）", e);
        }
    }

    /** 去掉注释行，只留代码 —— 注释里解释"不做什么"是允许的（本批次到处都在解释）。 */
    private static String codeOf(String relative) {
        return Arrays.stream(read(relative).split("\n"))
                .map(String::trim)
                .filter(l -> !l.startsWith("//") && !l.startsWith("*") && !l.startsWith("/*"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 🔴 AC2：场所这一块**不许出现 Spring 缓存抽象** —— 那就是「通用缓存层」。
     *
     * <p>AD-9 授权的是**计数器**（单个整数 + 可从库完整重算），不是「给查询结果加一层」。
     */
    @Test
    void noSpringCacheAnnotationsAnywhereInThePlaceModule() {
        for (String file : List.of("service/PlaceQueryService.java",
                "service/PlaceAttitudeCounters.java",
                "service/PlaceCommentQueryService.java",
                "service/PlaceCommentService.java")) {
            String code = codeOf(file);
            for (String banned : List.of("@Cacheable", "@CachePut", "@CacheEvict", "Caffeine")) {
                assertThat(code)
                        .as("🔴 %s 出现了 %s —— AD-9 没有解禁通用缓存层，要加先回架构改口径",
                                file, banned)
                        .doesNotContain(banned);
            }
        }
    }

    /**
     * 🔴 AC2：计数器只存**整数**。
     *
     * <p>把整页 DTO 塞进 Redis 就是"缓存整页结果"，那正是 AD-9 §3 点名排除的做法。
     * 判据：这个类只碰 {@code StringRedisTemplate} 的字符串值，不序列化任何响应对象。
     */
    @Test
    void theCounterStoresPlainIntegersNotSerializedResponses() {
        String code = codeOf("service/PlaceAttitudeCounters.java");
        for (String banned : List.of("Response", "ObjectMapper", "JsonMapper", "writeValueAsString")) {
            assertThat(code)
                    .as("🔴 计数器里出现了 %s —— 那是在缓存响应，不是在计数", banned)
                    .doesNotContain(banned);
        }
    }

    /**
     * 🔴 AC4：两个计数**不参与排序**。
     *
     * <p>列表只有两条排序分支：按距离、按最新（AD-2 Rule 5）。
     * 判据：排序代码（`sort` / `Comparator` / JPQL 的 `order by`）附近不出现计数字段。
     */
    @Test
    void attitudeCountsNeverFeedIntoSorting() {
        String code = codeOf("service/PlaceQueryService.java");
        // 取出所有排序相关的语句行，逐行确认里面没有计数。
        List<String> sortingLines = Arrays.stream(code.split("\n"))
                .filter(l -> l.contains("sort(") || l.contains("Comparator")
                        || l.toLowerCase(java.util.Locale.ROOT).contains("order by"))
                .toList();
        assertThat(sortingLines).as("排序代码不见了？改名了就改这条测试").isNotEmpty();
        for (String line : sortingLines) {
            assertThat(line)
                    .as("🔴 排序里用上了计数（AC4：只展示、不参与排序）：%s", line)
                    .doesNotContain("recommend")
                    .doesNotContain("Recommend")
                    .doesNotContain("attitude")
                    .doesNotContain("Counts");
        }
    }

    /**
     * 🔴 AC4：**不做降权、不做警示标**。
     *
     * <p>「先积累数据」是 PRD ③ 的原话 —— 在没有数据之前就按差评降权，等于用一个没人验证过的
     * 阈值去决定哪些店被用户看见。
     */
    @Test
    void noRankingPenaltyOrWarningBadgeLogic() {
        String code = codeOf("service/PlaceQueryService.java");
        for (String banned : List.of("penalt", "demote", "warningBadge", "lowRated", "boost")) {
            assertThat(code.toLowerCase(java.util.Locale.ROOT))
                    .as("🔴 出现了 %s —— 本版只展示、不降权、不警示（PRD ③）", banned)
                    .doesNotContain(banned.toLowerCase(java.util.Locale.ROOT));
        }
    }
}

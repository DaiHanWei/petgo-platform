package com.tailtopia.admin.dashboard.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板 18 项指标 key（V1.3.0 Story 3.2 AC2，PRD §1 ①-a 口径表，D-26）。
 * <b>顺序 = 口径表 #1～#18</b>；{@code key()} 是落库 / 图表用的 snake_case（架构 Naming Patterns：指标 key 小写下划线）。
 * {@code dualScope} = 帖子类 9 项（#3 #4 #8～#14，D-29）同时产出 ALL / REAL 两条序列；其余只接受 {@link MetricScope#ALL}。
 * 口径提示文案 key：{@code admin.v130.dashboard.metric.<key>.hint}（PRD 要求与 SQL 头注释同源，不另写一套）。
 */
public enum DashboardMetric {
    // 用户增长
    NEW_USERS(1, false),
    CUMULATIVE_USERS(2, false),          // 原「总安装用户数」，D-16 改名
    // 内容生产
    POSTING_USERS(3, true),
    NEW_POSTS(4, true),
    // 建档转化
    NEW_PET_OWNERS(5, false),            // 按人去重（D-28）
    CUMULATIVE_PET_OWNERS(6, false),
    DIARY_PET_OWNERS(7, false),
    // 互动质量
    INTERACTED_POSTS(8, true),
    SILENT_POSTS(9, true),
    ENGAGEMENT_SCORE(10, true),
    NEW_POSTS_SCORE(11, true),
    ALL_POSTS_AVG_SCORE(12, true),
    INTERACTED_POSTS_AVG_SCORE(13, true),
    ENGAGEMENT_SCORE_ALL_TIME(14, true),
    // 付费
    PAYING_USERS_CASH(15, false),
    PAYMENTS_CASH(16, false),
    PAYING_USERS_INCL_PAWCOIN(17, false),
    PAYMENTS_INCL_PAWCOIN(18, false);

    private final int number;
    private final boolean dualScope;

    DashboardMetric(int number, boolean dualScope) {
        this.number = number;
        this.dualScope = dualScope;
    }

    /** 口径表 #（1～18）。 */
    public int number() {
        return number;
    }

    /** 是否双口径（ALL + REAL）。 */
    public boolean dualScope() {
        return dualScope;
    }

    /** 落库 / 图表 key：snake_case（如 {@code cumulative_users}）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 口径提示 message key。 */
    public String hintKey() {
        return "admin.v130.dashboard.metric." + key() + ".hint";
    }

    /** 该指标应产出的口径集合：双口径 = {ALL, REAL}，否则 {ALL}。 */
    public Set<MetricScope> scopes() {
        return dualScope ? Set.of(MetricScope.ALL, MetricScope.REAL) : Set.of(MetricScope.ALL);
    }

    /** 由落库 key 反查；未知 key 抛 {@link IllegalArgumentException}。 */
    public static DashboardMetric fromKey(String key) {
        return Arrays.stream(values()).filter(m -> m.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown dashboard metric key: " + key));
    }

    /** 全部 key（按 # 顺序）。 */
    public static java.util.List<String> keys() {
        return Arrays.stream(values()).map(DashboardMetric::key).collect(Collectors.toList());
    }
}

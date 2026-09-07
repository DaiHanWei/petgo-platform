package com.tailtopia.content.rank;

/**
 * 打分与配比参数（V1.1.6 Story 16.2）。
 *
 * <p>⚠️ 本 story 的参数<b>由入参传入</b>，不读配置 —— 引擎要能在纯单测里穷举。
 * Story 16.4 负责把它们搬到配置中心；那不是优化项，而是因为 FR-95 与首页点赞同批发版，
 * 开发阶段线上不存在 {@code source=feed} 的点赞数据，<b>第一次校准必然在发版之后</b>（OQ-B1）。
 *
 * @param freshnessWeight   新鲜度权重（默认 0.6）
 * @param interactionWeight 互动度权重（默认 0.4）
 * @param commentWeight     评论相对点赞的权重（默认 2）
 * @param interactionP95    互动量的 95 分位归一化基准。🔴 {@code <= 0} 时互动度按 0 计
 *                          —— {@code ln(1 + P95)} 是分母，P95=0 会<b>除零</b>
 * @param honorBoost        带生效中装饰标签的加成（默认 1.3）
 * @param shuffleStrength   刷新抖动幅度 0–1（2026-09-01 产品拍板「刷新要明显换一批」）：
 *                          0=关闭（纯分数排序），越大换得越狠。默认 0.8。
 *                          ⚠️ 抖动的随机源是<b>序列种子</b>（见引擎 Input.shuffleSeed），
 *                          本参数只定幅度 —— 种子不变排序就不变，快照契约不受影响
 * @param mainSpeciesQuota  10 条窗口内主物种配额（默认 6）
 * @param otherSpeciesQuota 其他物种配额（默认 2）
 * @param generalQuota      通用配额（默认 2）
 * @param maxSameAttributeRun 同一属性最多连续几条（默认 2）
 * @param maxSameAuthorRun    同一作者最多连续几条（默认 1）
 * @param maxSameAuthorPerWindow 同一作者在 10 条窗口内最多几条（默认 2）
 * @param maxSameOtherSpeciesRun 同一<b>非主物种</b>最多连续几条（默认 1）
 */
public record RankParams(
        double freshnessWeight,
        double interactionWeight,
        double commentWeight,
        double interactionP95,
        double honorBoost,
        double shuffleStrength,
        int mainSpeciesQuota,
        int otherSpeciesQuota,
        int generalQuota,
        int maxSameAttributeRun,
        int maxSameAuthorRun,
        int maxSameAuthorPerWindow,
        int maxSameOtherSpeciesRun) {

    /**
     * 防扎堆四条的默认阈值。
     *
     * <p>⚠️ 刻意<b>不放进配置表</b>：AC1 的可调清单里没有它们，而防扎堆是"节奏底线"——
     * 调松它等于允许刷屏，调紧它会让小池子频繁挑不出人来。真要调，改这里并发版。
     */
    public static final int DEFAULT_MAX_SAME_ATTRIBUTE_RUN = 2;
    public static final int DEFAULT_MAX_SAME_AUTHOR_RUN = 1;
    public static final int DEFAULT_MAX_SAME_AUTHOR_PER_WINDOW = 2;
    public static final int DEFAULT_MAX_SAME_OTHER_SPECIES_RUN = 1;

    /** 上线初值（发版后按 OQ-B1 校准）。抖动幅度 0.8 与迁移默认值一致。 */
    public static RankParams defaults(double interactionP95) {
        return new RankParams(0.6, 0.4, 2.0, interactionP95, 1.3, 0.8, 6, 2, 2,
                DEFAULT_MAX_SAME_ATTRIBUTE_RUN, DEFAULT_MAX_SAME_AUTHOR_RUN,
                DEFAULT_MAX_SAME_AUTHOR_PER_WINDOW, DEFAULT_MAX_SAME_OTHER_SPECIES_RUN);
    }

    /**
     * 🛡 物种配比三项之和须等于窗口大小。
     *
     * <p>不校验的后果：运营把 6/2/2 改成 6/2/3，窗口凑不满或溢出 ——
     * 而那<b>不会报错</b>，只会让节奏莫名其妙，且极难被想到去查配置。
     *
     * <p>⚠️ 窗口大小自 Story 16.4 起<b>可配</b>，所以由调用方传入，不再读常量。
     */
    public boolean speciesQuotasConsistent(int window) {
        return mainSpeciesQuota + otherSpeciesQuota + generalQuota == window;
    }
}

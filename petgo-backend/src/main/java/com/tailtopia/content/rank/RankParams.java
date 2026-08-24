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
        int mainSpeciesQuota,
        int otherSpeciesQuota,
        int generalQuota,
        int maxSameAttributeRun,
        int maxSameAuthorRun,
        int maxSameAuthorPerWindow,
        int maxSameOtherSpeciesRun) {

    /** 上线初值（发版后按 OQ-B1 校准）。 */
    public static RankParams defaults(double interactionP95) {
        return new RankParams(0.6, 0.4, 2.0, interactionP95, 1.3, 6, 2, 2, 2, 1, 2, 1);
    }

    /**
     * 🛡 物种配比三项之和须等于窗口大小（16.4 AC4 的那道校验，这里先本地兜一层）。
     *
     * <p>不校验的后果：运营把 6/2/2 改成 6/2/3，窗口凑不满或溢出 ——
     * 而那<b>不会报错</b>，只会让节奏莫名其妙，且极难被想到去查配置。
     */
    public boolean speciesQuotasConsistent() {
        return mainSpeciesQuota + otherSpeciesQuota + generalQuota == AttributeTemplate.WINDOW;
    }
}

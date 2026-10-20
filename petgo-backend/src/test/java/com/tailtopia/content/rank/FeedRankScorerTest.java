package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：打分公式逐项（Story 16.2 · AC4）。 */
class FeedRankScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final RankParams P = RankParams.defaults(50);

    private static RankCandidate at(Instant createdAt, long likes, long comments) {
        return new RankCandidate(1L, 1L, FeedAttribute.FUN, null, createdAt, likes, comments);
    }

    // ── 新鲜度 = 1 / (1 + Δt/24) ────────────────────────────────────

    @Test
    void freshnessIsOneAtPublishAndHalfAfterOneDay() {
        assertThat(FeedRankScorer.freshness(NOW, NOW, 24)).isEqualTo(1.0);
        assertThat(FeedRankScorer.freshness(NOW.minus(Duration.ofHours(24)), NOW, 24))
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FeedRankScorer.freshness(NOW.minus(Duration.ofHours(48)), NOW, 24))
                .isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void freshnessDecaysMonotonically() {
        double prev = Double.MAX_VALUE;
        for (int h = 0; h <= 240; h += 12) {
            double f = FeedRankScorer.freshness(NOW.minus(Duration.ofHours(h)), NOW, 24);
            assertThat(f).isLessThan(prev);
            prev = f;
        }
    }

    /** ⚠️ 时钟偏移导致的「未来内容」按 0 小时算，不给超过 1 的分。 */
    @Test
    void futureCreatedAtDoesNotExceedOne() {
        assertThat(FeedRankScorer.freshness(NOW.plus(Duration.ofHours(5)), NOW, 24)).isEqualTo(1.0);
    }

    // ── 互动度 = ln(1 + 赞 + 2×评) / ln(1 + P95) ────────────────────

    @Test
    void interactionWeighsCommentsDouble() {
        // 1 条评论 == 2 个赞
        assertThat(FeedRankScorer.interaction(0, 1, P))
                .isEqualTo(FeedRankScorer.interaction(2, 0, P));
    }

    @Test
    void interactionIsOneAtP95() {
        assertThat(FeedRankScorer.interaction(50, 0, P))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void zeroInteractionScoresZero() {
        assertThat(FeedRankScorer.interaction(0, 0, P)).isEqualTo(0d);
    }

    /**
     * 🔴 P95 = 0 会除零（{@code ln(1+P95)} 是分母）。
     *
     * <p>不是防御性编程：16.4 会把 P95 做成「近 30 天动态重算」的值，重算失败或冷启动时它就是 0。
     */
    @Test
    void zeroP95DoesNotDivideByZero() {
        RankParams zero = RankParams.defaults(0);
        assertThat(FeedRankScorer.interaction(100, 100, zero)).isEqualTo(0d);
        assertThat(FeedRankScorer.interaction(0, 0, zero)).isEqualTo(0d);
        assertThat(FeedRankScorer.contentScore(at(NOW, 100, 100), NOW, zero)).isFinite();
    }

    /** ⚠️ 刻意不截断：超过 P95 的爆款互动度 > 1（调 weight 而不是加 clamp）。 */
    @Test
    void interactionAboveP95ExceedsOneOnPurpose() {
        assertThat(FeedRankScorer.interaction(5000, 0, P)).isGreaterThan(1.0);
    }

    // ── 内容分 = 0.6×新鲜度 + 0.4×互动度 ───────────────────────────

    @Test
    void contentScoreIsWeightedSum() {
        RankCandidate c = at(NOW.minus(Duration.ofHours(24)), 50, 0);
        assertThat(FeedRankScorer.contentScore(c, NOW, P))
                .isCloseTo(0.6 * 0.5 + 0.4 * 1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ── 最终分 = 内容分 × 曝光衰减 × 荣誉加成 × 限流系数 ────────────

    @Test
    void finalScoreMultipliesAllFourFactors() {
        RankCandidate c = at(NOW, 0, 0);
        double base = FeedRankScorer.contentScore(c, NOW, P);

        assertThat(FeedRankScorer.finalScore(c, NOW, P, 1.0, 1.0, 1.0, 1.0)).isEqualTo(base);
        assertThat(FeedRankScorer.finalScore(c, NOW, P, 0.3, 1.0, 1.0, 1.0))
                .isCloseTo(base * 0.3, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(FeedRankScorer.finalScore(c, NOW, P, 1.0, 1.3, 1.0, 1.0))
                .isCloseTo(base * 1.3, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(FeedRankScorer.finalScore(c, NOW, P, 1.0, 1.0, 0.2, 1.0))
                .isCloseTo(base * 0.2, org.assertj.core.data.Offset.offset(1e-12));
        // 四个一起
        assertThat(FeedRankScorer.finalScore(c, NOW, P, 0.3, 1.3, 0.2, 1.0))
                .isCloseTo(base * 0.3 * 1.3 * 0.2, org.assertj.core.data.Offset.offset(1e-12));
    }

    // ── V1.3.4 Story 1.2：科普半衰期 720h（FR-122.3）──────────────────

    private static RankCandidate edu(Instant createdAt) {
        return new RankCandidate(2L, 2L, FeedAttribute.EDU, null, createdAt, 0, 0);
    }

    private static RankParams withEduHalfLife(double h) {
        return new RankParams(P.freshnessWeight(), P.interactionWeight(), P.commentWeight(),
                P.interactionP95(), P.honorBoost(), P.shuffleStrength(), P.mainSpeciesQuota(),
                P.otherSpeciesQuota(), P.generalQuota(), P.maxSameAttributeRun(),
                P.maxSameAuthorRun(), P.maxSameAuthorPerWindow(), P.maxSameOtherSpeciesRun(), h);
    }

    /** AC2.3：90 天前的科普新鲜度 0.25，同龄日常 1/91。 */
    @Test
    void knowledgeUsesConfiguredHalfLife() {
        Instant ninetyDaysAgo = NOW.minus(Duration.ofHours(2160));
        RankCandidate k = edu(ninetyDaysAgo);
        RankCandidate d = at(ninetyDaysAgo, 0, 0);

        assertThat(FeedRankScorer.halfLifeHours(k, P)).isEqualTo(720d);
        assertThat(FeedRankScorer.halfLifeHours(d, P)).isEqualTo(24d);
        assertThat(FeedRankScorer.freshness(k.createdAt(), NOW, FeedRankScorer.halfLifeHours(k, P)))
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FeedRankScorer.freshness(d.createdAt(), NOW, FeedRankScorer.halfLifeHours(d, P)))
                .isCloseTo(1.0 / 91, org.assertj.core.data.Offset.offset(1e-9));
        // contentScore 用的是同一半衰期（无互动 ⇒ 内容分 = 新鲜度权重 × 新鲜度）
        assertThat(FeedRankScorer.contentScore(k, NOW, P))
                .isCloseTo(P.freshnessWeight() * 0.25, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** AC2.2：半衰期只看候选自身属性 —— 补位到 FUN / LIFE 槽位的科普同样用 720（打分器不感知槽位）。 */
    @Test
    void degradedKnowledgeStillUsesEduHalfLife() {
        RankCandidate k = edu(NOW.minus(Duration.ofHours(2160)));
        RankCandidate sameButFun = new RankCandidate(k.id(), k.authorId(), FeedAttribute.FUN,
                k.species(), k.createdAt(), k.likes(), k.comments());
        RankCandidate sameButLife = new RankCandidate(k.id(), k.authorId(), FeedAttribute.LIFE,
                k.species(), k.createdAt(), k.likes(), k.comments());

        assertThat(FeedRankScorer.contentScore(k, NOW, P))
                .isGreaterThan(FeedRankScorer.contentScore(sameButFun, NOW, P));
        assertThat(FeedRankScorer.contentScore(sameButFun, NOW, P))
                .isEqualTo(FeedRankScorer.contentScore(sameButLife, NOW, P));
    }

    /** AC2.4：半衰期调短，同一条老科普分更低（360h 时新鲜度 1/7）。 */
    @Test
    void shorterEduHalfLifeLowersOldKnowledge() {
        RankCandidate k = edu(NOW.minus(Duration.ofHours(2160)));

        double at720 = FeedRankScorer.contentScore(k, NOW, withEduHalfLife(720));
        double at360 = FeedRankScorer.contentScore(k, NOW, withEduHalfLife(360));

        assertThat(at720).isGreaterThan(at360);
        assertThat(FeedRankScorer.freshness(k.createdAt(), NOW, 360))
                .isCloseTo(1.0 / 7, org.assertj.core.data.Offset.offset(1e-9));
    }

    /** V1.4.0 ① Story 3.4 · AC4.1：作者系数 1.05 → 最终分恰为 1.05 倍（同条件两帖：Lv15 vs Lv14）。 */
    @Test
    void authorFactorMultipliesLast() {
        RankCandidate c = at(NOW, 3, 1);
        double lv14 = FeedRankScorer.finalScore(c, NOW, P, 1.0, 1.0, 1.0, 1.0);
        double lv15 = FeedRankScorer.finalScore(c, NOW, P, 1.0, 1.0, 1.0, 1.05);
        assertThat(lv15).isCloseTo(lv14 * 1.05, org.assertj.core.data.Offset.offset(1e-9));
    }
}

package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.species.ContentSpecies;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * L0：配比 / 打分 / 防扎堆 / 降级链（Story 16.2 · AC1–AC7）—— 全部纯单测，可穷举。
 *
 * <p>🔴 这些行为的共同点是<b>算错了也不报错</b>：只是刷起来节奏怪、排序看着有点怪。
 * 所以每条规则都要有一个能明确指认"就是这条"的断言，而不是"整体看着对"。
 */
class FeedRankEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final RankParams P = RankParams.defaults(50);

    private final FeedRankEngine engine = new FeedRankEngine();

    private static RankCandidate c(long id, long authorId, FeedAttribute attr, String species,
            long likes) {
        return new RankCandidate(id, authorId, attr, species, NOW, likes, 0);
    }

    private FeedRankEngine.Result rank(List<RankCandidate> pool, String mainSpecies, int wanted) {
        return engine.rank(new FeedRankEngine.Input(pool, mainSpecies, Map.of(), Set.of(),
                Map.of(), NOW, P), wanted);
    }

    private static List<FeedAttribute> attrs(FeedRankEngine.Result r) {
        return r.picked().stream().map(RankCandidate::attribute).toList();
    }

    private static List<Long> ids(FeedRankEngine.Result r) {
        return r.picked().stream().map(RankCandidate::id).toList();
    }

    /** 各属性各物种都充足、作者全不同的富池 —— 用来验证「不受池子不足干扰时」的理想行为。 */
    private static List<RankCandidate> richPool(List<String> species) {
        List<RankCandidate> pool = new ArrayList<>();
        long id = 1;
        for (FeedAttribute a : FeedAttribute.values()) {
            for (String s : species) {
                for (int i = 0; i < 20; i++) {
                    pool.add(c(id, id, a, s, 100 - i)); // 每条一个作者，排除防扎堆干扰
                    id++;
                }
            }
        }
        return pool;
    }

    // ── AC1 属性配比 ────────────────────────────────────────────────

    /** 池子充足时，产出序列的属性必须<b>逐槽位</b>等于模板（A→B→A）。 */
    @Test
    void attributeSequenceFollowsTemplatesAcrossThreeWindows() {
        FeedRankEngine.Result r = rank(richPool(List.of(ContentSpecies.GENERAL)), null, 30);

        List<FeedAttribute> expected = new ArrayList<>();
        expected.addAll(AttributeTemplate.A);
        expected.addAll(AttributeTemplate.B);
        expected.addAll(AttributeTemplate.A);
        assertThat(attrs(r)).containsExactlyElementsOf(expected);
        assertThat(r.attributeRelaxed()).isZero();
    }

    // ── AC2 物种配比 ────────────────────────────────────────────────

    /** 有主物种：10 条窗口内 主 6 / 其他 2 / 通用 2。 */
    @Test
    void speciesQuotaIsSixTwoTwoWithinWindow() {
        List<RankCandidate> pool = richPool(
                List.of(ContentSpecies.CAT, ContentSpecies.DOG, ContentSpecies.OTHER,
                        ContentSpecies.GENERAL));

        FeedRankEngine.Result r = rank(pool, ContentSpecies.CAT, 10);

        assertThat(r.picked()).hasSize(10);
        Map<SpeciesBucket, Long> byBucket = r.picked().stream().collect(
                java.util.stream.Collectors.groupingBy(
                        x -> SpeciesBucket.of(x.species(), ContentSpecies.CAT),
                        java.util.stream.Collectors.counting()));
        assertThat(byBucket).containsEntry(SpeciesBucket.MAIN, 6L)
                .containsEntry(SpeciesBucket.OTHER, 2L)
                .containsEntry(SpeciesBucket.GENERAL, 2L);
        assertThat(r.speciesRelaxed()).isZero();
    }

    /** 配额按窗口重置：两个窗口各自 6/2/2，而不是 20 条里 6/2/2。 */
    @Test
    void speciesQuotaResetsEachWindow() {
        FeedRankEngine.Result r = rank(richPool(
                List.of(ContentSpecies.CAT, ContentSpecies.DOG, ContentSpecies.GENERAL)),
                ContentSpecies.CAT, 20);

        for (int w = 0; w < 2; w++) {
            List<RankCandidate> window = r.picked().subList(w * 10, w * 10 + 10);
            long main = window.stream().filter(x -> ContentSpecies.CAT.equals(x.species())).count();
            assertThat(main).as("窗口 %d 的主物种条数", w).isEqualTo(6L);
        }
    }

    /**
     * 🔴 无主物种（无宠物档案 / 游客）：物种维度<b>不生效</b>，10 槽全按属性配比从全池挑。
     *
     * <p>这是「拿不到物种信号」，<b>不是</b>「按用户类型区别对待」—— 同一个用户建了档案后
     * 自动获得物种偏好，无需任何分档判定。
     */
    @Test
    void viewerWithoutMainSpeciesIgnoresSpeciesDimension() {
        // 全池都是狗内容；若物种维度错误地生效了，OTHER 桶配额只有 2，会大量放宽
        List<RankCandidate> pool = richPool(List.of(ContentSpecies.DOG));

        FeedRankEngine.Result r = rank(pool, null, 10);

        assertThat(r.picked()).hasSize(10);
        assertThat(r.speciesRelaxed()).isZero();
        assertThat(attrs(r)).containsExactlyElementsOf(AttributeTemplate.A);
    }

    // ── AC3 物种末端映射 ────────────────────────────────────────────

    /**
     * 🔴 物种「推不出来」的内容必须能排进去（归入通用池）。
     *
     * <p>若引擎把空值当成「不属于任何桶」，这些内容会<b>永远排不进来</b> ——
     * 而 14.1 的 resolver 对无信号内容返回的正是空值。
     */
    @Test
    void unknownSpeciesContentStillGetsRanked() {
        List<RankCandidate> pool = richPool(java.util.Collections.singletonList(null));

        FeedRankEngine.Result r = rank(pool, ContentSpecies.CAT, 10);

        assertThat(r.picked()).hasSize(10);
        assertThat(r.picked()).allSatisfy(x -> assertThat(x.species()).isNull());
    }

    /**
     * 🔴 「推不出来」必须与「显式配了通用」<b>共用同一份 GENERAL 配额</b>。
     *
     * <p>⚠️ 上一条测试（能排进去）判别力不够：把空值错误地归入 OTHER 桶，内容<b>照样能排进去</b>
     * （靠级别 2 放宽），只是抢了「其他物种」的配额。
     *
     * <p>🔴 <b>而且这条测试第一版也是假绿的</b>：当三个物种组的分数梯度完全一样时，
     * 放宽阶段会按 id 轮着挑，<b>恰好又凑出 6/2/2</b> —— 反证时才发现。
     * 所以这里刻意让主物种内容分数<b>整体更高</b>：正确实现下配额把它卡在 6 条，
     * 错误实现下通用配额空转、那 2 个槽位会被高分的主物种内容吃掉，变成 8 条。
     */
    @Test
    void unknownSpeciesSharesTheGeneralQuotaWithExplicitGeneral() {
        List<RankCandidate> pool = new ArrayList<>();
        long id = 1;
        for (FeedAttribute a : FeedAttribute.values()) {
            for (int i = 0; i < 20; i++) {
                pool.add(c(id, id, a, ContentSpecies.CAT, 500 - i)); // 主物种分数整体更高
                id++;
            }
            for (int i = 0; i < 20; i++) {
                pool.add(c(id, id, a, ContentSpecies.DOG, 50 - i));
                id++;
            }
            for (int i = 0; i < 20; i++) {
                pool.add(c(id, id, a, null, 50 - i)); // 物种推不出来
                id++;
            }
        }

        List<RankCandidate> window = rank(pool, ContentSpecies.CAT, 10).picked();

        assertThat(window.stream().filter(x -> ContentSpecies.CAT.equals(x.species())).count())
                .as("主物种（猫）—— 错误实现下会变成 8 条").isEqualTo(6L);
        assertThat(window.stream().filter(x -> ContentSpecies.DOG.equals(x.species())).count())
                .as("其他物种（狗）").isEqualTo(2L);
        assertThat(window.stream().filter(x -> x.species() == null).count())
                .as("推不出来（应占通用配额）").isEqualTo(2L);
    }

    // ── AC4 打分的四个乘法系数在引擎里真的生效 ───────────────────────

    /** 曝光衰减能把高分内容压到低分内容之后。 */
    @Test
    void exposureDecayCanFlipOrder() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 10),
                c(2L, 2L, FeedAttribute.FUN, null, 0));

        // 无衰减：id1 分高
        assertThat(ids(rank(pool, null, 1))).containsExactly(1L);

        // id1 已曝光（×0.3）→ 让位给 id2
        FeedRankEngine.Result r = engine.rank(new FeedRankEngine.Input(pool, null,
                Map.of(1L, 0.3), Set.of(), Map.of(), NOW, P), 1);
        assertThat(ids(r)).containsExactly(2L);
    }

    /** 🛡 荣誉加成生效；<b>不在集合内即回落 1.0</b>（标签到期的表现就是不在集合内）。 */
    @Test
    void honorBoostAppliesAndExpiresBackToOne() {
        // ⚠️ 分差要小于 1.3 倍才测得出加成：赞数 5 时 id1 的分是 0.782，×1.3 后的 id2 只有 0.78，
        //    加成会被"测不出来"而不是"不生效"。用 2 个赞（0.712 vs 0.78）留出余量。
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 2),
                c(2L, 2L, FeedAttribute.FUN, null, 0));

        // id2 带生效中标签 → ×1.3 反超
        FeedRankEngine.Result boosted = engine.rank(new FeedRankEngine.Input(pool, null,
                Map.of(), Set.of(2L), Map.of(), NOW, P), 1);
        assertThat(ids(boosted)).containsExactly(2L);

        // 标签到期（不在集合内）→ 回落 1.0，id1 恢复领先
        FeedRankEngine.Result expired = engine.rank(new FeedRankEngine.Input(pool, null,
                Map.of(), Set.of(), Map.of(), NOW, P), 1);
        assertThat(ids(expired)).containsExactly(1L);
    }

    /** 🛡 限流系数只是入口：本 story 一律 1.0；Epic 17 传入 0.2 时须真能降权。 */
    @Test
    void throttleSeamIsNeutralByDefaultAndEffectiveWhenSupplied() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 10),
                c(2L, 2L, FeedAttribute.FUN, null, 0));

        assertThat(ids(rank(pool, null, 1))).containsExactly(1L);

        FeedRankEngine.Result throttled = engine.rank(new FeedRankEngine.Input(pool, null,
                Map.of(), Set.of(), Map.of(1L, 0.2), NOW, P), 1);
        assertThat(ids(throttled)).containsExactly(2L);
    }

    // ── AC6 防扎堆四条 ─────────────────────────────────────────────

    /** 同一作者不得连续 —— 跳过取次高分。 */
    @Test
    void sameAuthorCannotBeAdjacent() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 100),
                c(2L, 1L, FeedAttribute.EDU, null, 100), // 同作者、分最高，但会连续
                c(3L, 2L, FeedAttribute.EDU, null, 50));

        FeedRankEngine.Result r = rank(pool, null, 2);

        assertThat(ids(r)).containsExactly(1L, 3L);
        assertThat(r.antiClumpOverridden()).isZero(); // 有干净的替补，不需要让步
    }

    /** 同一作者 10 条窗口内最多 2 条。 */
    @Test
    void sameAuthorCappedAtTwoPerWindow() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 100),
                c(2L, 1L, FeedAttribute.EDU, null, 100),
                c(3L, 1L, FeedAttribute.FUN, null, 100),
                c(10L, 1L, FeedAttribute.FUN, null, 100), // 第 3 条 a1 的 FUN，应被窗口上限挡住
                c(5L, 2L, FeedAttribute.EDU, null, 90),
                c(4L, 2L, FeedAttribute.FUN, null, 50),
                c(8L, 3L, FeedAttribute.LIFE, null, 80));

        FeedRankEngine.Result r = rank(pool, null, 5);

        assertThat(ids(r)).containsExactly(1L, 5L, 3L, 8L, 4L);
        long a1 = r.picked().stream().filter(x -> x.authorId() == 1L).count();
        assertThat(a1).isEqualTo(2L);
    }

    /**
     * 同一属性最多连续 2 条；🛡 <b>宁可扎堆也不空槽</b>。
     *
     * <p>池子里只有 FUN：属性放宽后连续 FUN 会撞上这条规则，但槽位仍必须填满 ——
     * 空槽对用户是「内容变少」，扎堆只是节奏差一点。让步次数计入 {@code antiClumpOverridden}。
     */
    @Test
    void sameAttributeRunIsCappedButSlotsAreNeverLeftEmpty() {
        List<RankCandidate> pool = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(c(i + 1, i + 1, FeedAttribute.FUN, null, 100 - i));
        }

        FeedRankEngine.Result r = rank(pool, null, 5);

        assertThat(r.picked()).hasSize(5); // 🛡 不空槽
        assertThat(r.attributeRelaxed()).isPositive(); // 级别 1 触发过
        assertThat(r.antiClumpOverridden()).isPositive(); // 且防扎堆确实让过步
    }

    /** 同一非主物种不得连续。 */
    @Test
    void sameNonMainSpeciesCannotBeAdjacent() {
        List<RankCandidate> pool = List.of(
                c(3L, 3L, FeedAttribute.FUN, ContentSpecies.DOG, 100),
                c(1L, 1L, FeedAttribute.EDU, ContentSpecies.DOG, 100), // 又是狗，会连续
                c(2L, 2L, FeedAttribute.EDU, ContentSpecies.CAT, 50));

        FeedRankEngine.Result r = rank(pool, ContentSpecies.CAT, 2);

        assertThat(ids(r)).containsExactly(3L, 2L);
    }

    /**
     * ⚠️ 这条规则比的是<b>具体物种</b>，不是 OTHER 这个桶。
     *
     * <p>桶里可能同时有狗和其他动物，按桶比会把「一条狗 + 一条仓鼠」也判成扎堆 ——
     * 那不是产品要防的事（产品防的是「连着三条狗」）。
     */
    @Test
    void twoDifferentNonMainSpeciesMayBeAdjacent() {
        List<RankCandidate> pool = List.of(
                c(3L, 3L, FeedAttribute.FUN, ContentSpecies.DOG, 100),
                c(4L, 4L, FeedAttribute.EDU, ContentSpecies.OTHER, 100),
                c(5L, 5L, FeedAttribute.EDU, ContentSpecies.CAT, 50));

        FeedRankEngine.Result r = rank(pool, ContentSpecies.CAT, 2);

        assertThat(ids(r)).containsExactly(3L, 4L);
    }

    // ── AC7 降级链级别 1 / 2 ────────────────────────────────────────

    /** 级别 1：属性池不足 → 其他属性补位，不空槽。 */
    @Test
    void level1FillsSlotsFromOtherAttributes() {
        List<RankCandidate> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) { // 只有 FUN 与 EDU，一条 LIFE 都没有
            pool.add(c(i + 1, i + 1, i % 2 == 0 ? FeedAttribute.FUN : FeedAttribute.EDU, null, 50));
        }

        FeedRankEngine.Result r = rank(pool, null, 10);

        assertThat(r.picked()).hasSize(10);
        assertThat(attrs(r)).doesNotContain(FeedAttribute.LIFE);
        assertThat(r.attributeRelaxed()).isEqualTo(2); // 模板里两个 LIFE 槽位被补位
    }

    /** 级别 2：物种池不足 → 放宽至通用池 → 再放宽至全池，仍不空槽。 */
    @Test
    void level2RelaxesSpeciesWithoutLeavingSlotsEmpty() {
        // 主物种是猫，但池子里一条猫内容都没有（全是通用）
        List<RankCandidate> pool = richPool(List.of(ContentSpecies.GENERAL));

        FeedRankEngine.Result r = rank(pool, ContentSpecies.CAT, 10);

        assertThat(r.picked()).hasSize(10);
        assertThat(r.speciesRelaxed()).isPositive();
        assertThat(attrs(r)).containsExactlyElementsOf(AttributeTemplate.A); // 属性节奏没被牺牲
    }

    // ── 稳定性与边界 ────────────────────────────────────────────────

    /**
     * 🔴 同样输入两次必须得到<b>完全相同</b>的序列。
     *
     * <p>不然序列快照（16.1）就失去意义了 —— 续算下一段时会与已下发的部分重复或跳过。
     */
    @Test
    void sameInputYieldsIdenticalSequence() {
        List<RankCandidate> pool = richPool(List.of(ContentSpecies.CAT, ContentSpecies.GENERAL));

        assertThat(ids(rank(pool, ContentSpecies.CAT, 30)))
                .isEqualTo(ids(rank(pool, ContentSpecies.CAT, 30)));
    }

    @Test
    void emptyPoolYieldsEmptyResult() {
        FeedRankEngine.Result r = rank(List.of(), ContentSpecies.CAT, 20);
        assertThat(r.picked()).isEmpty();
    }

    /** 池子比要求的少 → 返回已有的全部，不补 null、不抛错。 */
    @Test
    void smallPoolReturnsWhatExists() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, FeedAttribute.FUN, null, 10),
                c(2L, 2L, FeedAttribute.EDU, null, 10));

        assertThat(rank(pool, null, 20).picked()).hasSize(2);
    }

    /** 属性为 null 的候选（非公开成长日历）被剔除，而不是当成第四种属性。 */
    @Test
    void candidatesWithoutAttributeAreDropped() {
        List<RankCandidate> pool = List.of(
                c(1L, 1L, null, null, 100),
                c(2L, 2L, FeedAttribute.FUN, null, 10));

        assertThat(ids(rank(pool, null, 5))).containsExactly(2L);
    }

    /** 🛡 物种配比三项之和须等于窗口大小（16.4 那道校验的本地兜底）。 */
    @Test
    void speciesQuotaConsistencyIsCheckable() {
        assertThat(P.speciesQuotasConsistent()).isTrue();
        assertThat(new RankParams(0.6, 0.4, 2, 50, 1.3, 6, 2, 3, 2, 1, 2, 1)
                .speciesQuotasConsistent()).isFalse();
    }
}

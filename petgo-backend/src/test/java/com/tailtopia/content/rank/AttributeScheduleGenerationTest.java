package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0：由配比生成属性排期（Story 16.4）—— 🔴 补的是 16.2 留下的那处硬接缝。
 *
 * <p>16.2 时 A/B 模板是<b>手写字面量</b>，而 16.4 要把「属性配比」做成可调 ——
 * 改那三个数<b>不会自动改变模板顺序</b>。配置改了却不生效是最坏的一类 bug：
 * 不报错、不告警、只是节奏不对，且极难被想到去查配置。
 */
class AttributeScheduleGenerationTest {

    private static Map<FeedAttribute, Long> compose(List<FeedAttribute> t) {
        return t.stream().collect(Collectors.groupingBy(a -> a, Collectors.counting()));
    }

    /**
     * 🔴 <b>默认配比必须原样返回手写的 A/B</b>。
     *
     * <p>Story 16.2 的 AC1 把那两张表<b>逐槽位</b>钉死了。生成器只在运营真的改了配比时上场 ——
     * 让它顺手把默认顺序也换掉，等于悄悄改了已上线的首页节奏。
     */
    @Test
    void defaultQuotasReturnTheHandWrittenTables() {
        AttributeSchedule s = AttributeTemplate.forQuotas(5, 3, 2, 10);

        assertThat(s.variantA()).containsExactlyElementsOf(AttributeTemplate.A);
        assertThat(s.variantB()).containsExactlyElementsOf(AttributeTemplate.B);
        assertThat(s.window()).isEqualTo(10);
    }

    /** 🔴 改了配比就<b>真的</b>改顺序（这条一红就说明接缝还在）。 */
    @Test
    void changedQuotasActuallyChangeTheSequence() {
        AttributeSchedule s = AttributeTemplate.forQuotas(4, 4, 2, 10);

        assertThat(compose(s.variantA()))
                .isEqualTo(Map.of(FeedAttribute.FUN, 4L, FeedAttribute.EDU, 4L,
                        FeedAttribute.LIFE, 2L));
        assertThat(s.variantA()).isNotEqualTo(AttributeTemplate.A);
    }

    /** 生成的序列构成必须精确等于配比（穷举几组）。 */
    @Test
    void generatedCompositionMatchesQuotasExactly() {
        int[][] cases = {{4, 4, 2, 10}, {3, 3, 2, 8}, {5, 4, 3, 12}, {1, 1, 0, 2}, {6, 4, 2, 12}};
        for (int[] c : cases) {
            AttributeSchedule s = AttributeTemplate.forQuotas(c[0], c[1], c[2], c[3]);
            for (List<FeedAttribute> variant : List.of(s.variantA(), s.variantB())) {
                assertThat(variant).as("配比 %d/%d/%d 窗口 %d", c[0], c[1], c[2], c[3])
                        .hasSize(c[3]);
                assertThat(compose(variant).getOrDefault(FeedAttribute.FUN, 0L)).isEqualTo(c[0]);
                assertThat(compose(variant).getOrDefault(FeedAttribute.EDU, 0L)).isEqualTo(c[1]);
                assertThat(compose(variant).getOrDefault(FeedAttribute.LIFE, 0L)).isEqualTo(c[2]);
            }
        }
    }

    /** 🛡 生成的序列内部无同属性相邻（配比通过校验的前提下）。 */
    @Test
    void generatedSequenceHasNoAdjacentDuplicates() {
        int[][] cases = {{4, 4, 2, 10}, {3, 3, 2, 8}, {5, 4, 3, 12}, {6, 4, 2, 12}};
        for (int[] c : cases) {
            assertThat(AttributeTemplate.rejectUnusableQuotas(c[0], c[1], c[2], c[3]))
                    .as("这几组本身应通过校验").isNull();
            AttributeSchedule s = AttributeTemplate.forQuotas(c[0], c[1], c[2], c[3]);
            for (List<FeedAttribute> v : List.of(s.variantA(), s.variantB())) {
                for (int i = 1; i < v.size(); i++) {
                    assertThat(v.get(i)).as("配比 %d/%d/%d 槽位 %d 与 %d 相邻同属性",
                            c[0], c[1], c[2], i, i - 1).isNotEqualTo(v.get(i - 1));
                }
            }
        }
    }

    /** 🛡 跨窗口边界也不相邻（A 末 → B 首、B 末 → A 首）。 */
    @Test
    void generatedSequenceIsSafeAcrossWindowBoundaries() {
        AttributeSchedule s = AttributeTemplate.forQuotas(4, 4, 2, 10);

        for (int slot = 1; slot < s.window() * 4; slot++) {
            assertThat(s.at(slot)).as("全局槽位 %d 与 %d 相邻同属性", slot, slot - 1)
                    .isNotEqualTo(s.at(slot - 1));
        }
    }

    // ── 🛡 校验（AC4） ──────────────────────────────────────────────

    @Test
    void quotasMustSumToWindowSize() {
        assertThat(AttributeTemplate.rejectUnusableQuotas(5, 3, 3, 10))
                .contains("须等于窗口大小");
        assertThat(AttributeTemplate.rejectUnusableQuotas(5, 3, 1, 10))
                .contains("须等于窗口大小");
        assertThat(AttributeTemplate.rejectUnusableQuotas(5, 3, 2, 10)).isNull();
    }

    /**
     * 🔴 单项不得超过窗口的一半。
     *
     * <p>超过就<b>必然</b>出现同属性相邻（10 槽里放 6 个 FUN，鸽巢原理），
     * 「穿插」这件事本身失去意义 —— 而它同样不会报错。
     */
    @Test
    void noSingleQuotaMayExceedHalfTheWindow() {
        assertThat(AttributeTemplate.rejectUnusableQuotas(6, 3, 1, 10))
                .contains("不得超过窗口的一半");
        // 5/10 正好在边界上，可行（位次 1,3,5,7,9）
        assertThat(AttributeTemplate.rejectUnusableQuotas(5, 5, 0, 10)).isNull();
    }

    @Test
    void negativeAndTinyWindowsAreRejected() {
        assertThat(AttributeTemplate.rejectUnusableQuotas(-1, 5, 6, 10)).contains("不可为负");
        assertThat(AttributeTemplate.rejectUnusableQuotas(1, 0, 0, 1)).contains("窗口大小须 ≥ 2");
    }
}

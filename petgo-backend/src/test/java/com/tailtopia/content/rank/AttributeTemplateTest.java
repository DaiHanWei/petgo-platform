package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0：属性穿插模板（Story 16.2 · AC1）—— <b>穷举</b>，不抽样。
 *
 * <p>配比算错的表现是「刷起来节奏怪」，不报错、不崩，集成测试里看不出来。所以这里逐槽位钉死。
 */
class AttributeTemplateTest {

    private static Map<FeedAttribute, Long> compose(List<FeedAttribute> t) {
        return t.stream().collect(Collectors.groupingBy(a -> a, Collectors.counting()));
    }

    /** 两个模板都必须是 FUN 5 / EDU 3 / LIFE 2。 */
    @Test
    void bothTemplatesAreFiveThreeTwo() {
        Map<FeedAttribute, Long> expected = Map.of(
                FeedAttribute.FUN, 5L, FeedAttribute.EDU, 3L, FeedAttribute.LIFE, 2L);
        assertThat(compose(AttributeTemplate.A)).isEqualTo(expected);
        assertThat(compose(AttributeTemplate.B)).isEqualTo(expected);
        assertThat(AttributeTemplate.quotas()).isEqualTo(expected);
    }

    @Test
    void templatesAreExactlyTenSlots() {
        assertThat(AttributeTemplate.A).hasSize(AttributeTemplate.WINDOW);
        assertThat(AttributeTemplate.B).hasSize(AttributeTemplate.WINDOW);
    }

    /** 🛡 只有 A / B 两个模板 —— 交替，第三个窗口回到 A。 */
    @Test
    void windowsAlternateBetweenExactlyTwoTemplates() {
        assertThat(AttributeTemplate.forWindow(0)).isSameAs(AttributeTemplate.A);
        assertThat(AttributeTemplate.forWindow(1)).isSameAs(AttributeTemplate.B);
        assertThat(AttributeTemplate.forWindow(2)).isSameAs(AttributeTemplate.A);
        assertThat(AttributeTemplate.forWindow(3)).isSameAs(AttributeTemplate.B);
        assertThat(AttributeTemplate.forWindow(100)).isSameAs(AttributeTemplate.A);
    }

    /** 逐槽位穷举前三个窗口（A→B→A）。 */
    @Test
    void everySlotOfThreeWindowsMatchesTheTable() {
        List<FeedAttribute> expected = new ArrayList<>();
        expected.addAll(AttributeTemplate.A);
        expected.addAll(AttributeTemplate.B);
        expected.addAll(AttributeTemplate.A);

        List<FeedAttribute> actual = new ArrayList<>();
        for (int slot = 0; slot < 30; slot++) {
            actual.add(AttributeTemplate.at(slot));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    /**
     * 🛡 模板<b>内部</b>无同属性相邻。
     */
    @Test
    void noSameAttributeAdjacentWithinEitherTemplate() {
        for (List<FeedAttribute> t : List.of(AttributeTemplate.A, AttributeTemplate.B)) {
            for (int i = 1; i < t.size(); i++) {
                assertThat(t.get(i)).as("模板槽位 %d 与 %d 同属性相邻", i, i - 1)
                        .isNotEqualTo(t.get(i - 1));
            }
        }
    }

    /**
     * 🛡 <b>跨窗口边界</b>也不得同属性相邻（A 末 → B 首、B 末 → A 首）。
     *
     * <p>⚠️ 现在两个模板都以 FUN 开头、EDU 结尾，所以边界天然安全 ——
     * 但这是<b>字面量的巧合，不是不变量</b>。这条测试就是为「有人改了模板」而存在的。
     */
    @Test
    void noSameAttributeAcrossWindowBoundaries() {
        // 连续 5 个窗口逐槽位走一遍，任何相邻两槽都不同属性
        for (int slot = 1; slot < AttributeTemplate.WINDOW * 5; slot++) {
            assertThat(AttributeTemplate.at(slot))
                    .as("全局槽位 %d 与 %d 同属性相邻（窗口边界在 %d 的倍数处）",
                            slot, slot - 1, AttributeTemplate.WINDOW)
                    .isNotEqualTo(AttributeTemplate.at(slot - 1));
        }
    }
}

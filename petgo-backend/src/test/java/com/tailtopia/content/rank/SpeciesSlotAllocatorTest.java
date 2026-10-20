package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** L0：主物种配额按宠物数拆分（V1.4.0 multi-pet Story 3.2 · AC3 / PRD AC17）。 */
class SpeciesSlotAllocatorTest {

    private static Map<String, Integer> alloc(int quota, Map<String, Integer> counts) {
        return SpeciesSlotAllocator.allocate(quota, counts);
    }

    /** 🔴 AC17 算例：10 槽、2 猫 1 狗 → base 6 / 3、余 1 给宠物数多的猫 → 猫 7、狗 3。 */
    @Test
    void ac17TenSlotsTwoCatsOneDogIsSevenThree() {
        assertThat(alloc(10, Map.of("CAT", 2, "DOG", 1))).isEqualTo(Map.of("CAT", 7, "DOG", 3));
    }

    /** P-6：主物种 6 → 猫 4、狗 2（无余数）。 */
    @Test
    void sixSlotsTwoCatsOneDogIsFourTwo() {
        assertThat(alloc(6, Map.of("CAT", 2, "DOG", 1))).isEqualTo(Map.of("CAT", 4, "DOG", 2));
    }

    /** 单宠物 = 现状（整份配额给这一个物种）。 */
    @Test
    void singleSpeciesGetsWholeQuota() {
        assertThat(alloc(6, Map.of("CAT", 1))).isEqualTo(Map.of("CAT", 6));
        assertThat(alloc(6, Map.of("DOG", 3))).isEqualTo(Map.of("DOG", 6));
    }

    /** 余数给宠物数多的物种（PRD 字面），<b>不是</b>小数部分更大的狗（最大余数法会给 2 / 2）。 */
    @Test
    void remainderGoesToSpeciesWithMorePetsNotLargestFraction() {
        assertThat(alloc(4, Map.of("CAT", 3, "DOG", 2))).isEqualTo(Map.of("CAT", 3, "DOG", 1));
    }

    @Test
    void threeEqualSpecies() {
        assertThat(alloc(6, Map.of("CAT", 1, "DOG", 1, "OTHER", 1)))
                .isEqualTo(Map.of("CAT", 2, "DOG", 2, "OTHER", 2));
        // 同数同小数 → 固定顺序 CAT 优先
        assertThat(alloc(10, Map.of("CAT", 1, "DOG", 1, "OTHER", 1)))
                .isEqualTo(Map.of("CAT", 4, "DOG", 3, "OTHER", 3));
    }

    /**
     * 同宠物数 ⇒ 小数部分必然相同（{@code quota × count mod total} 只依赖 count），落到固定顺序 CAT, DOG, OTHER。
     */
    @Test
    void sameCountTieBrokenByFixedOrder() {
        // 7 槽、{CAT:2, DOG:2, OTHER:1}：base = 2 / 2 / 1（14/5、14/5、7/5），left 2 → 猫狗同数同小数 → CAT, DOG
        assertThat(alloc(7, Map.of("CAT", 2, "DOG", 2, "OTHER", 1)))
                .isEqualTo(Map.of("CAT", 3, "DOG", 3, "OTHER", 1));
        // 同数时固定顺序靠后的 OTHER 不抢 —— DOG 先于 OTHER
        assertThat(alloc(4, Map.of("DOG", 1, "OTHER", 1, "CAT", 1)))
                .isEqualTo(Map.of("CAT", 2, "DOG", 1, "OTHER", 1));
    }

    @Test
    void emptyOrZeroInputIsEmpty() {
        assertThat(alloc(6, Map.of())).isEmpty();
        assertThat(alloc(6, null)).isEmpty();
        assertThat(alloc(6, Map.of("CAT", 0))).isEmpty();
    }

    /** 总和恒等于 quota（穷举小范围）。 */
    @Test
    void sumAlwaysEqualsQuota() {
        for (int q = 0; q <= 20; q++) {
            for (int c = 0; c <= 5; c++) {
                for (int d = 0; d <= 5; d++) {
                    for (int o = 0; o <= 5; o++) {
                        Map<String, Integer> in = new HashMap<>();
                        if (c > 0) {
                            in.put("CAT", c);
                        }
                        if (d > 0) {
                            in.put("DOG", d);
                        }
                        if (o > 0) {
                            in.put("OTHER", o);
                        }
                        Map<String, Integer> out = alloc(q, in);
                        if (in.isEmpty()) {
                            assertThat(out).isEmpty();
                            continue;
                        }
                        assertThat(out.values().stream().mapToInt(Integer::intValue).sum())
                                .as("q=%d c=%d d=%d o=%d", q, c, d, o).isEqualTo(q);
                        assertThat(out.keySet()).isEqualTo(in.keySet());
                    }
                }
            }
        }
    }

    /** 结果与输入 Map 的迭代顺序无关。 */
    @Test
    void independentOfInputIterationOrder() {
        Map<String, Integer> linked = new LinkedHashMap<>();
        linked.put("OTHER", 1);
        linked.put("DOG", 1);
        linked.put("CAT", 1);
        for (int q = 0; q <= 12; q++) {
            Map<String, Integer> fromHash = alloc(q, new HashMap<>(linked));
            Map<String, Integer> fromTree = alloc(q, new TreeMap<>(linked));
            Map<String, Integer> fromLinked = alloc(q, linked);
            assertThat(fromHash).isEqualTo(fromTree).isEqualTo(fromLinked);
        }
        Map<String, Integer> two = new LinkedHashMap<>();
        two.put("DOG", 2);
        two.put("CAT", 3);
        assertThat(alloc(4, new HashMap<>(two))).isEqualTo(alloc(4, new TreeMap<>(two)))
                .isEqualTo(Map.of("CAT", 3, "DOG", 1));
    }
}

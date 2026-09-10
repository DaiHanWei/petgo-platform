package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * V1.3.0 批次 A · Story 1.1 · AC5（L0）：<b>三物种 × 全部自动事件的映射矩阵</b>。
 *
 * <p>本类是 AD-A4 的收口测试。它钉住的不是「某一条映射对不对」，而是<b>整张表都被人看过一遍</b>：
 * 旧实现的错误恰恰不是写错了某一行，而是压根没有「行」这个概念 —— 拼后缀让三个物种共用一份想象中的
 * 清单，于是通用宠物那 16 项与猫狗 31 项的错位，一次造出五处线上错误（两处错点亮、三处静默失效），
 * 且其中三处**用户永远不会报障**。
 *
 * <p>⚠️ 新增自动事件时本类会红（{@link #everyEventIsMappedForEverySpecies()}），这是刻意的：
 * 逼你为三个物种各写一行，包括「该物种没有对应节点」这一情况。
 */
class MilestoneAutoCompleteMapTest {

    private static final List<PetType> ALL_SPECIES = List.of(PetType.CAT, PetType.DOG, PetType.OTHER);

    // ===== 完整矩阵 =====

    @Nested
    @DisplayName("猫（31 项清单）")
    class Cat {

        @Test
        void allEvents() {
            assertThat(code(PetType.CAT, MilestoneAutoEvent.PROFILE_CREATED)).isEqualTo("C-S1");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.GROWTH_MOMENT_FIRST)).isEqualTo("C-S2");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.CARD_SHARED)).isEqualTo("C-S3");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.CONSULT_ARCHIVED)).isEqualTo("C-S4");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.PLATFORM_POST)).isEqualTo("C-S5");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.FIRST_COMMENT)).isEqualTo("C-S14");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.FIRST_LIKE)).isEqualTo("C-S15");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.GROWTH_MOMENT_10)).isEqualTo("C-M10");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.GROWTH_MOMENT_30)).isEqualTo("C-L5");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.HEALTH_RECORD_VACCINE)).isEqualTo("C-M3");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.HEALTH_RECORD_DEWORM)).isEqualTo("C-M4");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.HEALTH_RECORD_NEUTER)).isEqualTo("C-M9");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.CONSULT_CLOSED)).isEqualTo("C-M5");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.COMPANION_30_DAYS)).isEqualTo("C-M8");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.FIRST_BIRTHDAY)).isEqualTo("C-L1");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.COMPANION_100_DAYS)).isEqualTo("C-L2");
            assertThat(code(PetType.CAT, MilestoneAutoEvent.COMPANION_365_DAYS)).isEqualTo("C-L3");
        }
    }

    @Nested
    @DisplayName("狗（31 项清单）")
    class Dog {

        @Test
        void allEvents() {
            assertThat(code(PetType.DOG, MilestoneAutoEvent.PROFILE_CREATED)).isEqualTo("D-S1");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.GROWTH_MOMENT_FIRST)).isEqualTo("D-S2");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.CARD_SHARED)).isEqualTo("D-S3");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.CONSULT_ARCHIVED)).isEqualTo("D-S4");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.PLATFORM_POST)).isEqualTo("D-S5");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.FIRST_COMMENT)).isEqualTo("D-S14");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.FIRST_LIKE)).isEqualTo("D-S15");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.GROWTH_MOMENT_10)).isEqualTo("D-M10");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.GROWTH_MOMENT_30)).isEqualTo("D-L5");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.HEALTH_RECORD_VACCINE)).isEqualTo("D-M3");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.HEALTH_RECORD_DEWORM)).isEqualTo("D-M4");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.HEALTH_RECORD_NEUTER)).isEqualTo("D-M9");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.CONSULT_CLOSED)).isEqualTo("D-M5");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.COMPANION_30_DAYS)).isEqualTo("D-M8");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.FIRST_BIRTHDAY)).isEqualTo("D-L1");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.COMPANION_100_DAYS)).isEqualTo("D-L2");
            assertThat(code(PetType.DOG, MilestoneAutoEvent.COMPANION_365_DAYS)).isEqualTo("D-L3");
        }
    }

    @Nested
    @DisplayName("通用 / OTHER（16 项清单）—— 五处线上错误全在这里")
    class Other {

        /** ①② 两处**错点亮**：录疫苗曾点亮 G-M3「陪伴满 30 天」，录驱虫曾点亮 G-M4「记录满 10 条」。 */
        @Test
        void healthRecordEvents_noLongerLightUpUnrelatedNodes() {
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.HEALTH_RECORD_VACCINE))
                    .isEqualTo("G-M2"); // 完成第一次健康检查 / 疫苗（决策 A-1）
            // 通用清单没有驱虫 / 绝育节点 —— 不点亮任何条目（不是「先随便找一条」）。
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.HEALTH_RECORD_DEWORM)).isNull();
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.HEALTH_RECORD_NEUTER)).isNull();
        }

        /** ③④⑤ 三处**静默失效**：旧寻址指向通用清单根本不存在的 S14 / S15 / M10。 */
        @Test
        void silentlyDeadPaths_areNowAlive() {
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.FIRST_COMMENT)).isEqualTo("G-S7");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.FIRST_LIKE)).isEqualTo("G-S8");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.GROWTH_MOMENT_10)).isEqualTo("G-M4");
        }

        /** AD-A4.3 回归保护：三张清单位置恰好对齐的那些，一个都不许改。 */
        @Test
        void alreadyAlignedNodes_mustNotRegress() {
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.PROFILE_CREATED)).isEqualTo("G-S1");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.GROWTH_MOMENT_FIRST)).isEqualTo("G-S2");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.CARD_SHARED)).isEqualTo("G-S3");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.CONSULT_ARCHIVED)).isEqualTo("G-S4");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.PLATFORM_POST)).isEqualTo("G-S5");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.FIRST_BIRTHDAY)).isEqualTo("G-L1");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.COMPANION_100_DAYS)).isEqualTo("G-L2");
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.COMPANION_365_DAYS)).isEqualTo("G-L3");
        }

        /** AD-A4.3：定时扫描的 OTHER 特判（陪伴满 30 天 = G-M3）本来就是对的，收进表后仍须为 G-M3。 */
        @Test
        void companionThirtyDays_keepsExistingOtherSpecialCase() {
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.COMPANION_30_DAYS)).isEqualTo("G-M3");
        }

        /** 通用清单无「记录满 30 条」；真人问诊的对应节点 G-M1 接入属 Story 1.2，此刻显式为空。 */
        @Test
        void nodesAbsentFromGenericCatalog() {
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.GROWTH_MOMENT_30)).isNull();
            assertThat(code(PetType.OTHER, MilestoneAutoEvent.CONSULT_CLOSED)).isNull();
        }
    }

    // ===== 全表结构性约束 =====

    /** 每个事件都必须为三个物种各登记一行（值可以是 null，但不能"没这一行"）。 */
    @Test
    void everyEventIsMappedForEverySpecies() {
        for (MilestoneAutoEvent event : MilestoneAutoEvent.values()) {
            for (PetType species : ALL_SPECIES) {
                // 未登记 → codeOf 抛异常，这里就红；登记为 null 是合法取值，不会抛。
                MilestoneAutoCompleteMap.codeOf(species, event);
            }
        }
    }

    /**
     * 🔴 <b>本类最重要的一条</b>：映射出的每个 code 都必须真实存在于**该物种自己的**清单里。
     *
     * <p>五处错位中的 ③④⑤ 正是「指向该物种清单里不存在的 code」，而旧实现把它当成 no-op 吞了，
     * 于是三条合法路径从上线至今一次都没亮过、也没有任何人报障。这条断言让同类错误在 L0 当场暴露。
     */
    @Test
    void everyMappedCodeExistsInThatSpeciesCatalog() {
        for (PetType species : ALL_SPECIES) {
            List<String> catalogCodes = MilestoneCatalog.forType(species).stream()
                    .map(MilestoneDefinition::code).toList();
            for (MilestoneAutoEvent event : MilestoneAutoEvent.values()) {
                String code = MilestoneAutoCompleteMap.codeOf(species, event);
                if (code == null) {
                    continue; // 显式声明「该物种无此节点」
                }
                assertThat(catalogCodes)
                        .as("%s 的 %s 映射到 %s，但该 code 不在 %s 的清单里", species, event, code, species)
                        .contains(code);
            }
        }
    }

    /** 同一物种内不得两个事件指向同一个 code（否则两件不同的事会互相顶掉对方的完成态）。 */
    @Test
    void noTwoEventsShareOneCodeWithinASpecies() {
        for (PetType species : ALL_SPECIES) {
            List<String> mapped = java.util.Arrays.stream(MilestoneAutoEvent.values())
                    .map(e -> MilestoneAutoCompleteMap.codeOf(species, e))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(mapped).as("%s 的映射出现重复 code", species).doesNotHaveDuplicates();
        }
    }

    private static String code(PetType species, MilestoneAutoEvent event) {
        return MilestoneAutoCompleteMap.codeOf(species, event);
    }
}

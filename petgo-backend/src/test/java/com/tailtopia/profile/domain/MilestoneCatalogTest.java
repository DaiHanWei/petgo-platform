package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * L0 计数金标（Story 8.1 / FR-42）：钉死三套固定清单的数量与分级结构，防清单被误改。
 * 纯常量、无 Spring 上下文 / 无 DB → 云端 headless 可跑。
 */
class MilestoneCatalogTest {

    @Test
    void catHasThirtyOneMilestonesSplit16_10_5() {
        // 7.3：聚合里程碑 Lulus Pemula（C-S16）令 S 级 15→16、总 30→31。
        assertLevels(PetType.CAT, 16, 10, 5, 31);
    }

    @Test
    void dogHasThirtyOneMilestonesSplit16_10_5() {
        assertLevels(PetType.DOG, 16, 10, 5, 31);
    }

    @Test
    void otherHasSixteenMilestonesSplit9_4_3() {
        // 7.3：OTHER 的 Lulus Pemula 是 G-S9，S 级 8→9、总 15→16。
        assertLevels(PetType.OTHER, 9, 4, 3, 16);
    }

    @Test
    void lulusPemulaIsLastSystemAutoSNodePerType() {
        for (PetType type : PetType.values()) {
            String code = MilestoneCatalog.lulusPemulaCode(type);
            List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
            MilestoneDefinition lp = MilestoneCatalog.byCode(code);
            assertThat(MilestoneCatalog.isLulusPemula(code)).isTrue();
            assertThat(lp.level()).as("%s Lulus Pemula is S level", type).isEqualTo(MilestoneLevel.S);
            assertThat(lp.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            // 末位 sortOrder（不扰动既有 S1–S15/M/L 序）。
            assertThat(lp.sortOrder()).isEqualTo(list.get(list.size() - 1).sortOrder());
        }
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.CAT)).isEqualTo("C-S16");
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.DOG)).isEqualTo("D-S16");
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.OTHER)).isEqualTo("G-S9");
    }

    @Test
    void newbiePrereqSuffixesAreS1ThroughS5() {
        assertThat(MilestoneCatalog.NEWBIE_PREREQ_SUFFIXES)
                .containsExactlyInAnyOrder("S1", "S2", "S3", "S4", "S5");
        // 三清单 S1–S5 语义一致（统一后缀可判定）。
        for (String prefix : List.of("C", "D", "G")) {
            assertThat(MilestoneCatalog.byCode(prefix + "-S1").titleZh()).isEqualTo("宠物档案创建完成");
        }
    }

    private void assertLevels(PetType type, int s, int m, int l, int total) {
        List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
        assertThat(list).hasSize(total);
        assertThat(count(list, MilestoneLevel.S)).isEqualTo(s);
        assertThat(count(list, MilestoneLevel.M)).isEqualTo(m);
        assertThat(count(list, MilestoneLevel.L)).isEqualTo(l);
    }

    private long count(List<MilestoneDefinition> list, MilestoneLevel level) {
        return list.stream().filter(d -> d.level() == level).count();
    }

    @Test
    void codesAreUniqueAndSortOrderStrictlyAscendingPerType() {
        for (PetType type : PetType.values()) {
            List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
            Set<String> codes = list.stream().map(MilestoneDefinition::code).collect(Collectors.toSet());
            assertThat(codes).as("%s codes unique", type).hasSize(list.size());
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i).sortOrder())
                        .as("%s sortOrder strictly ascending", type)
                        .isGreaterThan(list.get(i - 1).sortOrder());
            }
        }
    }

    @Test
    void byCodeResolvesAcrossAllTypes() {
        assertThat(MilestoneCatalog.byCode("C-S1").titleZh()).isEqualTo("宠物档案创建完成");
        assertThat(MilestoneCatalog.byCode("D-M8").level()).isEqualTo(MilestoneLevel.M);
        assertThat(MilestoneCatalog.byCode("G-L1").trigger()).isEqualTo(MilestoneTriggerType.PUSH_PUBLISH);
        assertThat(MilestoneCatalog.byCode("UNKNOWN")).isNull();
    }

    @Test
    void healthComboNodesDependOnThreeHealthMilestones() {
        assertThat(MilestoneCatalog.HEALTH_COMBO.get("C-L4"))
                .containsExactlyInAnyOrder("C-M3", "C-M4", "C-M5");
        assertThat(MilestoneCatalog.HEALTH_COMBO.get("D-L4"))
                .containsExactlyInAnyOrder("D-M3", "D-M4", "D-M5");
        // 通用清单无健康组合节点（OTHER 无 L4）。
        assertThat(MilestoneCatalog.HEALTH_COMBO).doesNotContainKey("G-L4");
    }

    @Test
    void firstBirthdayNodesArePushPublishLevelL() {
        for (String code : List.of("C-L1", "D-L1", "G-L1")) {
            MilestoneDefinition d = MilestoneCatalog.byCode(code);
            assertThat(d.level()).isEqualTo(MilestoneLevel.L);
            assertThat(d.trigger()).isEqualTo(MilestoneTriggerType.PUSH_PUBLISH);
        }
    }
}

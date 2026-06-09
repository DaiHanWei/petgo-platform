package com.petgo.profile.domain;

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
    void catHasThirtyMilestonesSplit15_10_5() {
        assertLevels(PetType.CAT, 15, 10, 5, 30);
    }

    @Test
    void dogHasThirtyMilestonesSplit15_10_5() {
        assertLevels(PetType.DOG, 15, 10, 5, 30);
    }

    @Test
    void otherHasFifteenMilestonesSplit8_4_3() {
        assertLevels(PetType.OTHER, 8, 4, 3, 15);
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

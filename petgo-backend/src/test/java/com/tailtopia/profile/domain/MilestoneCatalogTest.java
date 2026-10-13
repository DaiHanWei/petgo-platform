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
    void catHasThirtyThreeMilestonesSplit18_10_5() {
        // 7.3：聚合里程碑 Lulus Pemula（C-S16）令 S 级 15→16、总 30→31。
        // V1.3.4 FR-126 +2（C-S17 打卡 / C-S18 性格测试）：S 级 16→18、总 31→33。
        assertLevels(PetType.CAT, 18, 10, 5, 33);
    }

    @Test
    void dogHasThirtyThreeMilestonesSplit18_10_5() {
        // V1.3.4 FR-126 +2（D-S17 / D-S18）。
        assertLevels(PetType.DOG, 18, 10, 5, 33);
    }

    @Test
    void otherHasEighteenMilestonesSplit11_4_3() {
        // 7.3：OTHER 的 Lulus Pemula 是 G-S9，S 级 8→9、总 15→16。
        // V1.3.4 FR-126 +2（G-S10 / G-S11）：S 级 9→11、总 16→18。
        assertLevels(PetType.OTHER, 11, 4, 3, 18);
    }

    @Test
    void lulusPemulaKeepsItsSortOrderAndNewItemsFollowIt() {
        for (PetType type : PetType.values()) {
            String code = MilestoneCatalog.lulusPemulaCode(type);
            List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
            MilestoneDefinition lp = MilestoneCatalog.byCode(code);
            assertThat(MilestoneCatalog.isLulusPemula(code)).isTrue();
            assertThat(lp.level()).as("%s Lulus Pemula is S level", type).isEqualTo(MilestoneLevel.S);
            assertThat(lp.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            // sortOrder 不变（V1.3.4 前的末位；V1.3.4 起其后追加打卡 / 性格测试两条，Lulus 成为倒数第 3）。
            assertThat(lp.sortOrder()).isEqualTo(type == PetType.OTHER ? 16 : 31);
            assertThat(list.get(list.size() - 3).code()).isEqualTo(code);
        }
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.CAT)).isEqualTo("C-S16");
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.DOG)).isEqualTo("D-S16");
        assertThat(MilestoneCatalog.lulusPemulaCode(PetType.OTHER)).isEqualTo("G-S9");
    }

    /** V1.3.4 FR-126（AD-18）：两条新项追加在 Lulus Pemula 之后，打卡在前、性格测试在后；不纳入新手任务。 */
    @Test
    void v134CheckinAndTailsonalityAppendedAfterLulus() {
        Object[][] expected = {
            {PetType.CAT, "C-S17", 32, "C-S18", 33},
            {PetType.DOG, "D-S17", 32, "D-S18", 33},
            {PetType.OTHER, "G-S10", 17, "G-S11", 18},
        };
        for (Object[] row : expected) {
            PetType type = (PetType) row[0];
            List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
            MilestoneDefinition checkin = list.get(list.size() - 2);
            MilestoneDefinition tailsonality = list.get(list.size() - 1);

            assertThat(checkin.code()).as("%s checkin code", type).isEqualTo(row[1]);
            assertThat(checkin.sortOrder()).isEqualTo(row[2]);
            assertThat(checkin.level()).isEqualTo(MilestoneLevel.S);
            assertThat(checkin.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            assertThat(checkin.titleZh()).isEqualTo("第一次场所打卡");
            assertThat(checkin.titleId()).isEqualTo("Check-in pertama");

            assertThat(tailsonality.code()).as("%s tailsonality code", type).isEqualTo(row[3]);
            assertThat(tailsonality.sortOrder()).isEqualTo(row[4]);
            assertThat(tailsonality.level()).isEqualTo(MilestoneLevel.S);
            assertThat(tailsonality.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            assertThat(tailsonality.titleZh()).isEqualTo("解锁性格测试结果");
            assertThat(tailsonality.titleId()).isEqualTo("Kenal kepribadian");
        }
        for (String code : List.of("C-S17", "C-S18", "D-S17", "D-S18", "G-S10", "G-S11")) {
            assertThat(MilestoneCatalog.isNewbiePrereq(code)).as("%s 不是新手前置", code).isFalse();
            assertThat(MilestoneCatalog.isLulusPemula(code)).isFalse();
        }
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

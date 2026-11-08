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
    void catHasThirtyFiveMilestonesSplit20_10_5() {
        // 7.3：聚合里程碑 Lulus Pemula（C-S16）令 S 级 15→16、总 30→31。
        // V1.3.4 FR-126 +2（C-S17 打卡 / C-S18 性格测试）：S 级 16→18、总 31→33。
        // V1.4.2 FR-154 +2（C-S19 关注 / C-S20 收藏）：S 级 18→20、总 33→35。
        assertLevels(PetType.CAT, 20, 10, 5, 35);
    }

    @Test
    void dogHasThirtyFiveMilestonesSplit20_10_5() {
        // V1.3.4 FR-126 +2（D-S17 / D-S18）；V1.4.2 FR-154 +2（D-S19 / D-S20）。
        assertLevels(PetType.DOG, 20, 10, 5, 35);
    }

    @Test
    void otherHasTwentyMilestonesSplit13_4_3() {
        // 7.3：OTHER 的 Lulus Pemula 是 G-S9，S 级 8→9、总 15→16。
        // V1.3.4 FR-126 +2（G-S10 / G-S11）：S 级 9→11、总 16→18。
        // V1.4.2 FR-154 +2（G-S12 / G-S13，决策日志 B1）：S 级 11→13、总 18→20。
        assertLevels(PetType.OTHER, 13, 4, 3, 20);
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
            // sortOrder 不变（V1.3.4 前的末位；V1.3.4 起其后追加打卡 / 性格测试两条、V1.4.2 再追加关注 / 收藏两条，
            // Lulus 成为倒数第 5）。
            assertThat(lp.sortOrder()).isEqualTo(type == PetType.OTHER ? 16 : 31);
            assertThat(list.get(list.size() - 5).code()).isEqualTo(code);
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
            // V1.4.2 起其后再追加账号级两条（见下一个用例），故取倒数第 4 / 第 3。
            MilestoneDefinition checkin = list.get(list.size() - 4);
            MilestoneDefinition tailsonality = list.get(list.size() - 3);

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

    /**
     * V1.4.2 FR-154（D-4 / 决策日志 B1）：账号级两条追加在清单末尾，关注在前、收藏在后；
     * 通用是 G-S12 / G-S13（不是 S19 / S20）；不纳入新手任务（1.5.0 才计入）。
     */
    @Test
    void v142AccountLevelFollowAndCollectAppendedLast() {
        Object[][] expected = {
            {PetType.CAT, "C-S19", 34, "C-S20", 35},
            {PetType.DOG, "D-S19", 34, "D-S20", 35},
            {PetType.OTHER, "G-S12", 19, "G-S13", 20},
        };
        for (Object[] row : expected) {
            PetType type = (PetType) row[0];
            List<MilestoneDefinition> list = MilestoneCatalog.forType(type);
            MilestoneDefinition follow = list.get(list.size() - 2);
            MilestoneDefinition collect = list.get(list.size() - 1);

            assertThat(follow.code()).as("%s follow code", type).isEqualTo(row[1]);
            assertThat(follow.sortOrder()).isEqualTo(row[2]);
            assertThat(follow.level()).isEqualTo(MilestoneLevel.S);
            assertThat(follow.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            assertThat(follow.titleZh()).isEqualTo("第一次关注一个人");
            assertThat(follow.titleId()).isEqualTo("Teman pertama");

            assertThat(collect.code()).as("%s collect code", type).isEqualTo(row[3]);
            assertThat(collect.sortOrder()).isEqualTo(row[4]);
            assertThat(collect.level()).isEqualTo(MilestoneLevel.S);
            assertThat(collect.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            assertThat(collect.titleZh()).isEqualTo("第一次收藏内容");
            assertThat(collect.titleId()).isEqualTo("Simpan pertama");
        }
        for (String code : List.of("C-S19", "C-S20", "D-S19", "D-S20", "G-S12", "G-S13")) {
            assertThat(MilestoneCatalog.isNewbiePrereq(code)).as("%s 不是新手前置", code).isFalse();
            assertThat(MilestoneCatalog.isLulusPemula(code)).isFalse();
        }
        assertThat(MilestoneCatalog.byCode("G-S19")).isNull();
        assertThat(MilestoneCatalog.byCode("G-S20")).isNull();
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

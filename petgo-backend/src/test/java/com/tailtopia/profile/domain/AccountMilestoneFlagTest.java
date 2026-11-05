package com.tailtopia.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（V1.4.2 Story 2.1 · D-4 / 决策日志 B1）：账号级 flag ↔ 完整 code 精确映射，不按后缀切分。 */
class AccountMilestoneFlagTest {

    @Test
    void codesAreFullSpeciesCodesFromTheMap() {
        assertThat(AccountMilestoneFlag.FIRST_FOLLOW_USER.codes()).containsExactly("C-S19", "D-S19", "G-S12");
        assertThat(AccountMilestoneFlag.FIRST_COLLECT_CONTENT.codes()).containsExactly("C-S20", "D-S20", "G-S13");
        assertThat(AccountMilestoneFlag.FIRST_FOLLOW_USER.codeOf(PetType.OTHER)).isEqualTo("G-S12");
        assertThat(AccountMilestoneFlag.FIRST_FOLLOW_USER.event()).isEqualTo(MilestoneAutoEvent.FIRST_FOLLOW_USER);
    }

    @Test
    void ofCodeResolvesExactlyTheSixCodes() {
        for (String c : List.of("C-S19", "D-S19", "G-S12")) {
            assertThat(AccountMilestoneFlag.ofCode(c)).contains(AccountMilestoneFlag.FIRST_FOLLOW_USER);
            assertThat(AccountMilestoneFlag.isAccountLevel(c)).isTrue();
        }
        for (String c : List.of("C-S20", "D-S20", "G-S13")) {
            assertThat(AccountMilestoneFlag.ofCode(c)).contains(AccountMilestoneFlag.FIRST_COLLECT_CONTENT);
            assertThat(AccountMilestoneFlag.isAccountLevel(c)).isTrue();
        }
    }

    /** 🔴 近似 code 一律为假：G-S19 / G-S20 不存在（B1），后缀相同的 C-S1 / C-S2 也不是。 */
    @Test
    void nearMissesAreNotAccountLevel() {
        for (String c : List.of("C-S1", "C-S2", "G-S19", "G-S20", "S19", "c-s19", "C-S190", "", " C-S19")) {
            assertThat(AccountMilestoneFlag.isAccountLevel(c)).as(c).isFalse();
            assertThat(AccountMilestoneFlag.ofCode(c)).as(c).isEmpty();
        }
        assertThat(AccountMilestoneFlag.isAccountLevel(null)).isFalse();
        assertThat(AccountMilestoneFlag.ofCode(null)).isEmpty();
    }

    /** flag_key 列 VARCHAR(32)：枚举名必须放得下（也必须在迁移的 CHECK 全集里）。 */
    @Test
    void flagKeysFitTheColumn() {
        for (AccountMilestoneFlag f : AccountMilestoneFlag.values()) {
            assertThat(f.name().length()).isLessThanOrEqualTo(32);
        }
        assertThat(AccountMilestoneFlag.values()).extracting(Enum::name)
                .containsExactly("FIRST_FOLLOW_USER", "FIRST_COLLECT_CONTENT");
    }

    /** 每个账号级 code 都真实存在于该物种清单、且是 S 级 SYSTEM_AUTO。 */
    @Test
    void everyCodeIsAnSLevelSystemAutoCatalogEntry() {
        for (AccountMilestoneFlag f : AccountMilestoneFlag.values()) {
            for (PetType t : PetType.values()) {
                String code = f.codeOf(t);
                MilestoneDefinition d = MilestoneCatalog.byCode(code);
                assertThat(d).as(code).isNotNull();
                assertThat(MilestoneCatalog.forType(t)).contains(d);
                assertThat(d.level()).isEqualTo(MilestoneLevel.S);
                assertThat(d.trigger()).isEqualTo(MilestoneTriggerType.SYSTEM_AUTO);
            }
        }
    }
}

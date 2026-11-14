package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.PetType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * V1.4.2 Story 2.2 · L0（无 DB）：迁移 ⑥ {@code V20261104_2040} 的文本守卫 —— 与运行时口径逐字对齐。
 * 真跑与重放见 {@link AccountMilestoneBackfillMigrationIntegrationTest}（L1）。
 */
class AccountMilestoneBackfillMigrationScriptTest {

    private static final String SCRIPT = "db/migration/V20261104_2040__milestone_account_flags_backfill.sql";
    private static final String LAUNCH_SCRIPT = "db/migration/V20261018_1503__growth_launch_backfill.sql";

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /** 去掉 SQL 行注释，避免头注释里的说明文字干扰断言。 */
    private static String body(String sql) {
        return sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
    }

    private static String eligibleCte(String sql) {
        int start = sql.indexOf("WITH eligible AS (");
        assertThat(start).as("eligible CTE").isNotNegative();
        int end = sql.indexOf("\n)", start);
        return sql.substring(start, end);
    }

    @Test
    void eligibleCteIsVerbatimSameAsLaunchBackfill() throws IOException {
        assertThat(eligibleCte(body(read(SCRIPT)))).isEqualTo(eligibleCte(body(read(LAUNCH_SCRIPT))));
    }

    @Test
    void idemKeyFormatsMatchGrowthIdemKeys() throws IOException {
        String sql = body(read(SCRIPT));
        // SQL 拼法换成样例值后与 Java 生成的键一致
        assertThat("BACKFILL142:" + 7 + ":" + "FIRST_FOLLOW_USER")
                .isEqualTo(GrowthIdemKeys.backfillAccountMilestone(7L, "FIRST_FOLLOW_USER"));
        assertThat(sql).contains("'BACKFILL142:' || fl.user_id || ':' || fl.flag_key");
        assertThat("MILESTONE_ACCT:" + 7 + ":" + "FIRST_FOLLOW_USER")
                .isEqualTo(GrowthIdemKeys.milestoneAccount(7L, "FIRST_FOLLOW_USER"));
        assertThat(sql).contains("'MILESTONE_ACCT:' || fl.user_id || ':' || fl.flag_key");
        assertThat(sql).contains("ON CONFLICT (idem_key) DO NOTHING");
    }

    @Test
    void codeTableMatchesAccountMilestoneFlagForEverySpecies() throws IOException {
        String sql = body(read(SCRIPT));
        // V1.5.0 onboarding-recall Story 2.2：LULUS_PEMULA 加入枚举后，1.4.2 这条已应用的补算脚本（冻结、不得改）
        // 只覆盖它当时的两个 flag；Lulus Pemula 的补算在 V1.5.0 的 V20261115_0320（Story 2-4）。
        for (AccountMilestoneFlag flag : java.util.List.of(AccountMilestoneFlag.FIRST_FOLLOW_USER,
                AccountMilestoneFlag.FIRST_COLLECT_CONTENT)) {
            for (PetType type : PetType.values()) {
                assertThat(sql).as("%s × %s", flag, type).containsPattern(
                        "\\('" + flag.name() + "',\\s*'" + type.name() + "',\\s*'" + flag.codeOf(type) + "'\\)");
            }
        }
    }

    @Test
    void doesNotTouchPopupOrCelebrationColumnsAndOnlyReadsUserFollowsAndContentCollections() throws IOException {
        String sql = body(read(SCRIPT));
        assertThat(sql).doesNotContain("celebrate_from_level").doesNotContain("celebrate_to_level")
                .doesNotContain("backfill_popup_pending = true")
                .doesNotContain("backfill_active_days").doesNotContain("backfill_milestone_count")
                .doesNotContain("backfill_milestone_exp")
                .doesNotContain("place_collections")
                .doesNotContain("notifications");
        assertThat(sql).contains("f.source = 'USER'").contains("p.status = 'ACTIVE'")
                .contains("ON CONFLICT (user_id, flag_key) DO NOTHING");
    }

    /** 复审 2-2：两段 flag 都排除注销已受理（任何状态）的账号，与运行时 onFlagTriggered 逐字对齐。 */
    @Test
    void bothFlagInsertsExcludeAnyAccountDeletion() throws IOException {
        String sql = body(read(SCRIPT));
        String exclusion = "AND NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id)\n";
        int flagSection = sql.indexOf("INSERT INTO milestone_completions");
        String flags = sql.substring(0, flagSection);
        assertThat(flags.split(java.util.regex.Pattern.quote(exclusion), -1))
                .as("段 ① 两条 INSERT 各一次").hasSize(3);
    }
}

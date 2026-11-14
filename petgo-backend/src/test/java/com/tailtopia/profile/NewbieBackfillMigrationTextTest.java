package com.tailtopia.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.MilestoneAutoCompleteMap;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.NewbieTask;
import com.tailtopia.profile.domain.PetType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：V1.5.0 onboarding-recall Story 2.4 · AC1 / AC5 —— 存量补算迁移的文本守卫（不连库）。
 * 真执行、幂等重放在 L1 {@code NewbieBackfillMigrationIntegrationTest}。
 */
class NewbieBackfillMigrationTextTest {

    private static final String SCRIPT =
            "src/main/resources/db/migration/V20261115_0320__newbie_tasks_launch_backfill.sql";

    private static String body() throws IOException {
        // 去掉注释行：注释里如实提到了「不写通知」「is_automated」等字样，扫代码不扫说明。
        return Files.readString(Path.of(SCRIPT)).lines()
                .filter(l -> !l.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void writesAllSevenTaskKeysAsBackfill() throws IOException {
        String sql = body();
        for (NewbieTask t : NewbieTask.values()) {
            assertThat(sql).as(t.name()).contains("'" + t.name() + "'");
        }
        assertThat(sql).contains("'BACKFILL'").contains("ON CONFLICT (user_id, task_key) DO NOTHING");
    }

    @Test
    void idemKeyFormatMatchesGrowthIdemKeys() throws IOException {
        String sql = body();
        assertThat("BACKFILL150:" + 7 + ":LULUS_PEMULA").isEqualTo(GrowthIdemKeys.backfillNewbie(7L));
        assertThat(sql).contains("'BACKFILL150:' || nf.user_id || ':LULUS_PEMULA'");
        assertThat("MILESTONE_ACCT:" + 7 + ":LULUS_PEMULA")
                .isEqualTo(GrowthIdemKeys.milestoneAccount(7L, AccountMilestoneFlag.LULUS_PEMULA.name()));
        assertThat(sql).contains("'MILESTONE_ACCT:' || nf.user_id || ':LULUS_PEMULA'")
                .contains("ON CONFLICT (idem_key) DO NOTHING")
                .contains("'LAUNCH_BACKFILL', 20, 'MILESTONE', 'LULUS_PEMULA'");
    }

    @Test
    void milestoneCodesComeFromTheMapNotSuffixes() throws IOException {
        String sql = body();
        for (MilestoneAutoEvent e : new MilestoneAutoEvent[] {MilestoneAutoEvent.GROWTH_MOMENT_FIRST,
                MilestoneAutoEvent.FIRST_LIKE}) {
            for (PetType t : PetType.values()) {
                assertThat(sql).as("%s × %s", e, t).contains("'" + MilestoneAutoCompleteMap.codeOf(t, e) + "'");
            }
        }
        for (PetType t : PetType.values()) {
            assertThat(sql).containsPattern("\\('LULUS_PEMULA',\\s*'" + t.name() + "',\\s*'"
                    + Pattern.quote(AccountMilestoneFlag.LULUS_PEMULA.codeOf(t)) + "'\\)");
        }
    }

    @Test
    void doesNotNotifyOrPopupAndDoesNotFilterAutomatedLikes() throws IOException {
        String sql = body();
        assertThat(sql).doesNotContain("INSERT INTO notifications")
                .doesNotContain("celebrate_from_level").doesNotContain("celebrate_to_level")
                .doesNotContain("is_automated");
        // backfill_popup_pending 只在新建账户行时给默认 false，不改已有行。
        assertThat(sql).doesNotContain("backfill_popup_pending =");
    }

    @Test
    void eligibleMatchesLaunchBackfill() throws IOException {
        String sql = body();
        assertThat(sql).contains("WHERE u.role = 'USER' AND u.account_type = 'REAL' AND u.deleted_at IS NULL"
                + " AND u.status = 'ACTIVE'")
                .contains("NOT EXISTS (SELECT 1 FROM account_deletions ad WHERE ad.user_id = u.id AND ad.status <> 'PENDING')");
    }
}

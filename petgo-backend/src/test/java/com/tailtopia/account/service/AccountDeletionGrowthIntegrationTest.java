package com.tailtopia.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.growth.domain.ReverseReason;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.service.AccrualCommand;
import com.tailtopia.growth.service.GrowthAccountDeletionService;
import com.tailtopia.growth.service.GrowthIdemKeys;
import com.tailtopia.growth.service.GrowthLedgerService;
import com.tailtopia.growth.service.ReverseCommand;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V1.4.0 ① Story 1.2 · AC7（L1，需 Docker postgres）：注销删三张表（含反向流水自引用 FK），别人不受影响。 */
class AccountDeletionGrowthIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private GrowthLedgerService ledger;

    @Autowired
    private GrowthAccountDeletionService growthDeletion;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant AT = Instant.parse("2026-10-18T05:00:00Z");

    private int rows(String table, long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private void seed(long userId) {
        ledger.accrue(new AccrualCommand(userId, RuleCode.POST_PUBLISHED, null, GrowthIdemKeys.post(8_000_000L + userId),
                "CONTENT_POST", "1", null, AT));
        ledger.accrue(new AccrualCommand(userId, RuleCode.LIKE_RECEIVED, null, GrowthIdemKeys.likeReceived(1L, userId),
                "CONTENT_POST", "1", null, AT));
        ledger.reverse(new ReverseCommand(GrowthIdemKeys.likeReceived(1L, userId), ReverseReason.UNLIKE,
                GrowthIdemKeys.SOURCE_UNLIKE, null));
    }

    @Test
    void deletesOnlyThatUsersRows() {
        long gone = newUser().getId();
        long kept = newUser().getId();
        seed(gone);
        seed(kept);
        assertThat(rows("growth_ledger", gone)).isEqualTo(3);

        growthDeletion.deleteByUserId(gone);
        growthDeletion.deleteByUserId(gone); // 幂等可重跑

        assertThat(rows("growth_ledger", gone)).isZero();
        assertThat(rows("growth_daily_counters", gone)).isZero();
        assertThat(rows("growth_accounts", gone)).isZero();
        assertThat(rows("growth_ledger", kept)).isEqualTo(3);
        assertThat(rows("growth_accounts", kept)).isEqualTo(1);
    }
}

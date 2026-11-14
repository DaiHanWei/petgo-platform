package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.EntitlementExpiryReminderJob;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-4 · AC4.8 —— 7 天内到期发一条、8 天不发、已提醒不再发、
 * 两个大类两条、走 {@code sendWithoutPush}。
 */
class EntitlementExpiryReminderIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntitlementCreditService credits;
    @Autowired
    private EntitlementExpiryReminderJob job;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private long notices(long uid) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND type = 'PACK_CREDIT_EXPIRING'",
                Long.class, uid);
        return n == null ? 0 : n;
    }

    private void expireIn(long uid, String type, Duration d) {
        jdbc.update("UPDATE entitlement_credits SET expires_at = now() + CAST(? AS interval) WHERE user_id = ? AND credit_type = ?",
                d.toHours() + " hours", uid, type);
    }

    @Test
    void remindsWithinSevenDaysOncePerFamily() {
        User u = newUser();
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.KTP_HD_KTP, 1, "k-" + u.getId());
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.GIFT_1K, 5, "g-" + u.getId());
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.TAILSONALITY, 1, "t-" + u.getId());
        expireIn(u.getId(), "KTP_HD:KTP", Duration.ofDays(3));
        expireIn(u.getId(), "GIFT_1K", Duration.ofDays(6));
        expireIn(u.getId(), "TAILSONALITY", Duration.ofDays(8));

        job.run(Instant.now());
        assertThat(notices(u.getId())).isEqualTo(2);
        job.run(Instant.now());
        assertThat(notices(u.getId())).isEqualTo(2);
        // 不推送：任务只调 sendWithoutPush（通知表无推送列，推送桩断言留本地 L1 按推送网关 bean 补）。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?"
                + " AND type = 'PACK_CREDIT_EXPIRING' AND deep_link_type = 'PACK_CREDIT_EXPIRING' AND title IS NOT NULL",
                Long.class, u.getId())).isEqualTo(2L);
    }
}

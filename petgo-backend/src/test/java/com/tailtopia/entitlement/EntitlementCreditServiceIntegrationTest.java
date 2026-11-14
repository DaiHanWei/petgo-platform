package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.entitlement.domain.CreditFamily;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.CreditSummary;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.GrantResult;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L1（需 postgres + redis，云端只写不跑）：Story 4-1 AC3.6 / AC5。 */
class EntitlementCreditServiceIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntitlementCreditService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private static GrantCommand pack(long uid, CreditType type, int qty, String key) {
        return new GrantCommand(uid, type, qty, CreditSource.PACK, "1", key, null, null, null, null);
    }

    private int rows(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM entitlement_credits WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void sameKeySameTypeReplaysOneRow() {
        long uid = newUser().getId();
        GrantResult a = service.grant(pack(uid, CreditType.GIFT_1K, 5, "PACK:" + uid));
        GrantResult b = service.grant(pack(uid, CreditType.GIFT_1K, 4, "PACK:" + uid));
        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isTrue();
        assertThat(b.creditId()).isEqualTo(a.creditId());
        assertThat(b.expiresAt()).isEqualTo(a.expiresAt());
        assertThat(rows(uid)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentTypeTwoRows() {
        long uid = newUser().getId();
        service.grant(pack(uid, CreditType.GIFT_1K, 5, "PACK:x" + uid));
        service.grant(pack(uid, CreditType.TAILSONALITY, 1, "PACK:x" + uid));
        assertThat(rows(uid)).isEqualTo(2);
    }

    @Test
    void anyFromPackRejected() {
        long uid = newUser().getId();
        assertThatThrownBy(() -> service.grant(pack(uid, CreditType.KTP_HD_ANY, 1, "k" + uid)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.ENTITLEMENT_TYPE_NOT_ALLOWED);
        assertThat(rows(uid)).isZero();
    }

    @Test
    void deletedUserRejected() {
        long uid = newUser().getId();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", uid);
        assertThatThrownBy(() -> service.grant(pack(uid, CreditType.GIFT_1K, 1, "k" + uid)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.ENTITLEMENT_USER_INACTIVE);
    }

    @Test
    void joinsCallerTransaction() {
        long uid = newUser().getId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            service.grant(GrantCommand.activity(uid, CreditType.KTP_HD_ANY, 1, 9L, "ACT:9:POST:" + uid, "A", "A"));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows(uid)).isZero();
    }

    @Test
    void summaryCountsOnlyAvailable() {
        long uid = newUser().getId();
        service.grant(pack(uid, CreditType.KTP_HD_KTP, 2, "s1" + uid));
        service.grant(GrantCommand.activity(uid, CreditType.KTP_HD_ANY, 1, 3L, "s2" + uid, "A", "A"));
        service.grant(pack(uid, CreditType.GIFT_1K, 5, "s3" + uid));
        jdbc.update("UPDATE entitlement_credits SET granted_at = now() - interval '200 days', "
                + "expires_at = now() - interval '1 day' WHERE user_id = ? AND credit_type = 'GIFT_1K'", uid);
        CreditSummary s = service.summary(uid);
        assertThat(s.totalRemaining()).isEqualTo(3);
        assertThat(s.families()).hasSize(1);
        assertThat(s.families().get(0).family()).isEqualTo(CreditFamily.KTP_HD);
        assertThat(s.families().get(0).types()).hasSize(2);
    }
}

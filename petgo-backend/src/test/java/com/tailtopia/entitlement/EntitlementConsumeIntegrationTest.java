package com.tailtopia.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.domain.CreditUseKind;
import com.tailtopia.entitlement.service.ConsumeCommand;
import com.tailtopia.entitlement.service.ConsumeResult;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** L1（需 postgres + redis，云端只写不跑）：Story 4-1 AC4.8。 */
class EntitlementConsumeIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntitlementCreditService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private long grant(long uid, CreditType type, int qty, String key) {
        GrantCommand c = type == CreditType.KTP_HD_ANY
                ? GrantCommand.activity(uid, type, qty, 1L, key, "A", "A")
                : new GrantCommand(uid, type, qty, CreditSource.PACK, "1", key, null, null, null, null);
        return service.grant(c).creditId();
    }

    /** 共享测试库不清表：目标 id 由 uid 派生，保证每次运行 use_key 唯一（code review #1）。 */
    private ConsumeResult consume(long uid, CreditUseKind kind, CreditType type, int qty, long target) {
        long targetId = uid * 10_000 + target;
        return tx.execute(s -> service.consume(new ConsumeCommand(uid, kind, type, qty, targetId, "Mochi")));
    }

    private int used(long creditId) {
        return jdbc.queryForObject("SELECT used FROM entitlement_credits WHERE id = ?", Integer.class, creditId);
    }

    private int uses(long uid) {
        return jdbc.queryForObject("SELECT count(*) FROM entitlement_credit_uses WHERE user_id = ?", Integer.class, uid);
    }

    @Test
    void exactTemplateBeforeAny() {
        long uid = newUser().getId();
        long any = grant(uid, CreditType.KTP_HD_ANY, 1, "a" + uid);
        long ktp = grant(uid, CreditType.KTP_HD_KTP, 1, "b" + uid);
        ConsumeResult r = consume(uid, CreditUseKind.KTP_HD, CreditType.KTP_HD_KTP, 1, 100);
        assertThat(r.creditIds()).containsExactly(ktp);
        assertThat(r.firstSource()).isEqualTo(CreditSource.PACK);
        assertThat(used(any)).isZero();
        ConsumeResult r2 = consume(uid, CreditUseKind.KTP_HD, CreditType.KTP_HD_KTP, 1, 101);
        assertThat(r2.creditIds()).containsExactly(any);
        assertThat(r2.firstSource()).isEqualTo(CreditSource.ACTIVITY);
    }

    @Test
    void earliestExpiryFirstAndAcrossBatches() {
        long uid = newUser().getId();
        long late = grant(uid, CreditType.GIFT_1K, 5, "l" + uid);
        long early = grant(uid, CreditType.GIFT_1K, 3, "e" + uid);
        jdbc.update("UPDATE entitlement_credits SET expires_at = expires_at - interval '10 days' WHERE id = ?", early);
        ConsumeResult r = consume(uid, CreditUseKind.GIFT, CreditType.GIFT_1K, 5, 7);
        assertThat(r.creditIds()).containsExactly(early, late);
        assertThat(used(early)).isEqualTo(3);
        assertThat(used(late)).isEqualTo(2);
        assertThat(uses(uid)).isEqualTo(2);
    }

    @Test
    void expiredBatchIgnoredAndInsufficientWritesNothing() {
        long uid = newUser().getId();
        long c = grant(uid, CreditType.TAILSONALITY, 1, "t" + uid);
        jdbc.update("UPDATE entitlement_credits SET granted_at = now() - interval '200 days', "
                + "expires_at = now() - interval '1 second' WHERE id = ?", c);
        assertThatThrownBy(() -> consume(uid, CreditUseKind.TAILSONALITY, CreditType.TAILSONALITY, 1, 5))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getType()).isEqualTo(ErrorTypes.ENTITLEMENT_INSUFFICIENT);
        assertThat(used(c)).isZero();
        assertThat(uses(uid)).isZero();
    }

    @Test
    void sameUseKeyIsAlreadyUsed() {
        long uid = newUser().getId();
        long c = grant(uid, CreditType.TAILSONALITY, 2, "u" + uid);
        assertThat(consume(uid, CreditUseKind.TAILSONALITY, CreditType.TAILSONALITY, 1, 9).alreadyUsed()).isFalse();
        ConsumeResult again = consume(uid, CreditUseKind.TAILSONALITY, CreditType.TAILSONALITY, 1, 9);
        assertThat(again.alreadyUsed()).isTrue();
        assertThat(again.consumed()).isZero();
        assertThat(used(c)).isEqualTo(1);
    }

    @Test
    void concurrentSameTargetConsumesOnce() throws Exception {
        long uid = newUser().getId();
        long c = grant(uid, CreditType.TAILSONALITY, 2, "c" + uid);
        List<ConsumeResult> results = race(2, i -> consume(uid, CreditUseKind.TAILSONALITY, CreditType.TAILSONALITY, 1, 42));
        assertThat(results.stream().filter(r -> !r.alreadyUsed()).count()).isEqualTo(1);
        assertThat(used(c)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentTargetsDoNotOverConsume() throws Exception {
        long uid = newUser().getId();
        long c = grant(uid, CreditType.TAILSONALITY, 1, "d" + uid);
        List<Object> results = race(2, i -> {
            try {
                return consume(uid, CreditUseKind.TAILSONALITY, CreditType.TAILSONALITY, 1, 1000 + i);
            } catch (AppException e) {
                return e;
            }
        });
        assertThat(results.stream().filter(r -> r instanceof AppException).count()).isEqualTo(1);
        assertThat(used(c)).isEqualTo(1);
    }

    private <T> List<T> race(int n, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            fs.add(pool.submit(() -> {
                start.await();
                return task.apply(k);
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) {
            out.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return out;
    }
}

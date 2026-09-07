package com.tailtopia.shared.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 0904 复审修复：事务内的幂等键写入推迟到提交后，回滚不留键。 */
class IdempotencyServiceTxTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final IdempotencyService svc = new IdempotencyService(redis);

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storesImmediatelyOutsideTransaction() {
        when(redis.opsForValue()).thenReturn(ops);
        svc.store("k", 42L);
        verify(ops).set(eq("idem:k"), eq("42"), any(Duration.class));
    }

    @Test
    void deferredUntilCommitInsideTransaction() {
        when(redis.opsForValue()).thenReturn(ops);
        TransactionSynchronizationManager.initSynchronization();
        svc.store("k", 42L);
        verify(ops, never()).set(any(), any(), any(Duration.class));
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(ops).set(eq("idem:k"), eq("42"), any(Duration.class));
    }

    @Test
    void rollbackLeavesNoKey() {
        when(redis.opsForValue()).thenReturn(ops);
        TransactionSynchronizationManager.initSynchronization();
        svc.store("k", 42L);
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(ops, never()).set(any(), any(), any(Duration.class));
    }
}

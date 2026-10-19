package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * L0：V1.4.0 multi-pet Story 2.2 · code review #1 —— 到账发放后的 reconcile 在 AFTER_COMMIT 另开 REQUIRES_NEW 事务执行，
 * 失败只记日志、不冒泡。
 */
class PetSlotGrantedReconcileListenerTest {

    private final PetSlotStateService state = mock(PetSlotStateService.class);

    @Test
    void annotatedAfterCommitWithoutMethodTransaction() throws NoSuchMethodException {
        Method m = PetSlotGrantedReconcileListener.class.getMethod("onGranted", PetSlotGrantedEvent.class);
        assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(m.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void reconcilesInsideTheNewTransaction() {
        AtomicInteger txRuns = new AtomicInteger();
        TransactionOperations tx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                txRuns.incrementAndGet();
                return action.doInTransaction(null);
            }
        };
        new PetSlotGrantedReconcileListener(state, tx).onGranted(new PetSlotGrantedEvent(7L, PetSlotTrigger.RENEWED));
        assertThat(txRuns).hasValue(1);
        verify(state).reconcile(7L, PetSlotTrigger.RENEWED);
    }

    @Test
    void reconcileFailureIsSwallowed() {
        when(state.reconcile(7L, PetSlotTrigger.RENTAL_ADDED)).thenThrow(new IllegalStateException("boom"));
        TransactionOperations tx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        assertThatCode(() -> new PetSlotGrantedReconcileListener(state, tx)
                .onGranted(new PetSlotGrantedEvent(7L, PetSlotTrigger.RENTAL_ADDED))).doesNotThrowAnyException();
    }

    @Test
    void productionTemplateIsRequiresNew() {
        org.springframework.transaction.PlatformTransactionManager tm =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        PetSlotGrantedReconcileListener l = new PetSlotGrantedReconcileListener(state, tm);
        Object tx = org.springframework.test.util.ReflectionTestUtils.getField(l, "newTx");
        assertThat(((TransactionDefinition) tx).getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}

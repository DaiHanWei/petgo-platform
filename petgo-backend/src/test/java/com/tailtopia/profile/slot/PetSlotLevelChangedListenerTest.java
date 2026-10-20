package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.event.GrowthLevelChangedEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** L0：V1.4.0 multi-pet Story 2.1 · AC5 —— 升 / 降两条事件都只调 reconcile(LEVEL_CHANGED)；异常不冒泡；注解形态。 */
class PetSlotLevelChangedListenerTest {

    private final PetSlotStateService state = mock(PetSlotStateService.class);
    private final PetSlotLevelChangedListener listener = new PetSlotLevelChangedListener(state);

    @Test
    void levelUpReconcilesWithLevelChanged() {
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 4, 5));
        verify(state).reconcile(7L, PetSlotTrigger.LEVEL_CHANGED);
    }

    @Test
    void levelDownAlsoOnlyReconcilesWithLevelChanged() {
        listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 10, 9));
        verify(state).reconcile(eq(7L), eq(PetSlotTrigger.LEVEL_CHANGED));
        org.mockito.Mockito.verifyNoMoreInteractions(state);
    }

    @Test
    void failureIsSwallowed() {
        when(state.reconcile(anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onLevelChanged(new GrowthLevelChangedEvent(7L, 1, 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void isAsyncAfterCommitWithFallbackAndNoOwnTransaction() throws Exception {
        Method m = PetSlotLevelChangedListener.class.getMethod("onLevelChanged", GrowthLevelChangedEvent.class);
        TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
        org.assertj.core.api.Assertions.assertThat(tel).isNotNull();
        org.assertj.core.api.Assertions.assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        org.assertj.core.api.Assertions.assertThat(tel.fallbackExecution()).isTrue();
        org.assertj.core.api.Assertions.assertThat(m.getAnnotation(Async.class)).isNotNull();
        // Spring 6.1+：@TransactionalEventListener 上挂默认传播的 @Transactional 启动即失败；事务由 reconcile 自己开。
        org.assertj.core.api.Assertions.assertThat(m.getAnnotation(Transactional.class)).isNull();
        org.assertj.core.api.Assertions.assertThat(PetSlotStateService.class
                .getMethod("reconcile", long.class, PetSlotTrigger.class).getAnnotation(Transactional.class))
                .isNotNull();
    }
}

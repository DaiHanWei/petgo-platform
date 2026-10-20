package com.tailtopia.gift.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.event.GiftUndeliveredEvent;
import com.tailtopia.gift.service.GiftRefundService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** V1.4.0 gift Story 1.2 · AC2（L0）：首次退回失败 → 记失败；记失败也失败 → 不向上抛。 */
class GiftUndeliveredListenerTest {

    private GiftRefundService refunds;
    private GiftUndeliveredListener listener;

    @BeforeEach
    void setUp() {
        refunds = mock(GiftRefundService.class);
        listener = new GiftUndeliveredListener(refunds);
    }

    @Test
    void successAttemptDoesNotRecordFailure() {
        when(refunds.attempt(41L)).thenReturn(true);
        listener.onUndelivered(new GiftUndeliveredEvent(41L));
        verify(refunds).attempt(41L);
        verify(refunds, never()).recordFailure(anyLong(), anyString());
    }

    @Test
    void attemptFailureRecordsSimpleClassName() {
        when(refunds.attempt(41L)).thenThrow(new IllegalStateException("secret message"));
        listener.onUndelivered(new GiftUndeliveredEvent(41L));
        verify(refunds).recordFailure(41L, "IllegalStateException");
    }

    @Test
    void recordFailureFailureIsSwallowed() {
        when(refunds.attempt(41L)).thenThrow(new IllegalStateException());
        doThrow(new RuntimeException()).when(refunds).recordFailure(anyLong(), anyString());
        assertThatCode(() -> listener.onUndelivered(new GiftUndeliveredEvent(41L))).doesNotThrowAnyException();
    }

    @Test
    void listensAfterCommit() throws NoSuchMethodException {
        Method m = GiftUndeliveredListener.class.getMethod("onUndelivered", GiftUndeliveredEvent.class);
        org.assertj.core.api.Assertions.assertThat(m.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}

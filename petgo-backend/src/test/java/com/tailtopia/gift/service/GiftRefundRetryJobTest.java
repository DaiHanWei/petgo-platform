package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.repository.GiftOrderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

/** V1.4.0 gift Story 1.2 · AC3（L0）：只取 attempts < 5、每批 ≤ 100、单笔失败继续下一笔、整批异常不抛。 */
class GiftRefundRetryJobTest {

    private GiftOrderRepository orders;
    private GiftRefundService refunds;
    private GiftRefundRetryJob job;

    @BeforeEach
    void setUp() {
        orders = mock(GiftOrderRepository.class);
        refunds = mock(GiftRefundService.class);
        job = new GiftRefundRetryJob(orders, refunds);
    }

    @Test
    void scansOnlyBelowMaxAttemptsWithBatchLimit() {
        when(orders.findRefundRetryIds(anyInt(), anyInt())).thenReturn(List.of());
        job.run();
        verify(orders).findRefundRetryIds(5, 100);
        verifyNoInteractions(refunds);
    }

    @Test
    void singleFailureDoesNotStopTheBatch() {
        when(orders.findRefundRetryIds(5, 100)).thenReturn(List.of(1L, 2L, 3L));
        when(refunds.attempt(2L)).thenThrow(new IllegalStateException());

        job.run();

        InOrder o = inOrder(refunds);
        o.verify(refunds).attempt(1L);
        o.verify(refunds).attempt(2L);
        o.verify(refunds).recordFailure(2L, "IllegalStateException");
        o.verify(refunds).attempt(3L);
        verify(refunds, never()).recordFailure(1L, "IllegalStateException");
    }

    @Test
    void recordFailureErrorIsSwallowedAndBatchContinues() {
        when(orders.findRefundRetryIds(5, 100)).thenReturn(List.of(1L, 2L));
        when(refunds.attempt(1L)).thenThrow(new IllegalStateException());
        doThrow(new RuntimeException()).when(refunds).recordFailure(anyLong(), anyString());

        assertThatCode(job::run).doesNotThrowAnyException();
        verify(refunds).attempt(2L);
    }

    @Test
    void scanFailureIsSwallowed() {
        when(orders.findRefundRetryIds(anyInt(), anyInt())).thenThrow(new RuntimeException());
        assertThatCode(job::run).doesNotThrowAnyException();
    }

    @Test
    void scheduledEveryTenMinutesByDefault() throws NoSuchMethodException {
        Scheduled s = GiftRefundRetryJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(s.fixedDelayString()).isEqualTo("${petgo.gift.refund-retry-ms:600000}");
    }
}

package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.dto.PawCoinWalletView;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：PawCoin 余额与流水查询（mock wallet/repo）。余额组合、游标 hasMore/nextCursor、空态、
 * DTO 只暴露展示字段（Review 护栏）。
 */
@ExtendWith(MockitoExtension.class)
class PawCoinQueryServiceTest {

    @Mock
    PawCoinWalletService walletService;
    @Mock
    PawCoinTransactionRepository txns;

    private PawCoinQueryService service() {
        return new PawCoinQueryService(walletService, txns);
    }

    private static PawCoinTransaction txn(long delta, PawCoinTxnType type, long epochMilli) {
        PawCoinTransaction t = PawCoinTransaction.of(1L, delta, type, "PAYMENT_INTENT", 5L, "grp");
        ReflectionTestUtils.setField(t, "createdAt", Instant.ofEpochMilli(epochMilli));
        return t;
    }

    @Test
    void viewReturnsBalanceAndItemsWithoutMoreWhenUnderLimit() {
        when(walletService.balanceOf(1L)).thenReturn(120_000L);
        when(txns.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of(txn(10_000L, PawCoinTxnType.TOPUP, 2000),
                        txn(-5_000L, PawCoinTxnType.SPEND, 1000)));

        PawCoinWalletView v = service().view(1L, null, 20);

        assertThat(v.balance()).isEqualTo(120_000L);
        assertThat(v.items()).hasSize(2);
        assertThat(v.hasMore()).isFalse();
        assertThat(v.nextCursor()).isNull();
        // DTO 只暴露展示字段（delta/type/refType/createdAt），无 id/refId/entryGroup。
        assertThat(v.items().get(0).delta()).isEqualTo(10_000L);
        assertThat(v.items().get(0).type()).isEqualTo("TOPUP");
        assertThat(v.items().get(1).delta()).isEqualTo(-5_000L);
    }

    @Test
    void viewSetsHasMoreAndNextCursorWhenOverLimit() {
        when(walletService.balanceOf(1L)).thenReturn(0L);
        // limit=1 → 服务请求 limit+1=2 条；返回 2 条即 hasMore。
        when(txns.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of(txn(10_000L, PawCoinTxnType.TOPUP, 2000),
                        txn(-5_000L, PawCoinTxnType.SPEND, 1000)));

        PawCoinWalletView v = service().view(1L, null, 1);

        assertThat(v.items()).hasSize(1); // 截断到 limit
        assertThat(v.hasMore()).isTrue();
        assertThat(v.nextCursor()).isEqualTo("2000"); // 末条(截断后)epochMillis
    }

    @Test
    void viewEmptyLedgerReturnsZeroItemsNoCursor() {
        when(walletService.balanceOf(1L)).thenReturn(0L);
        when(txns.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        PawCoinWalletView v = service().view(1L, null, 20);

        assertThat(v.balance()).isZero();
        assertThat(v.items()).isEmpty();
        assertThat(v.hasMore()).isFalse();
        assertThat(v.nextCursor()).isNull();
    }
}

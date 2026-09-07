package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.PawCoinTransaction;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.dto.PawCoinWalletView;
import com.tailtopia.pay.repository.PawCoinTransactionRepository;
import com.tailtopia.shared.paging.KeysetCursor;
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
        return txn(delta, type, epochMilli, 1L);
    }

    /** id 是复合游标的 tie-breaker（2026-08-18 起流水游标是 (createdAt, id)）。 */
    private static PawCoinTransaction txn(long delta, PawCoinTxnType type, long epochMilli,
            long id) {
        PawCoinTransaction t = PawCoinTransaction.of(1L, delta, type, "PAYMENT_INTENT", 5L, "grp");
        ReflectionTestUtils.setField(t, "createdAt", Instant.ofEpochMilli(epochMilli));
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    @Test
    void viewReturnsBalanceAndItemsWithoutMoreWhenUnderLimit() {
        when(walletService.balanceOf(1L)).thenReturn(120_000L);
        when(txns.findPageBefore(anyLong(), any(), anyLong(), any(Pageable.class)))
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
        when(txns.findPageBefore(anyLong(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(txn(10_000L, PawCoinTxnType.TOPUP, 2000, 7L),
                        txn(-5_000L, PawCoinTxnType.SPEND, 1000, 6L)));

        PawCoinWalletView v = service().view(1L, null, 1);

        assertThat(v.items()).hasSize(1); // 截断到 limit
        assertThat(v.hasMore()).isTrue();
        // 游标是 base64url 的 (createdAt, id) —— 对客户端不透明，这里按语义反解断言，
        // 而不是硬编码一个字面量（硬编码会把「编码格式」当成契约锁死）。
        var decoded = KeysetCursor.decodeOrNull(v.nextCursor());
        assertThat(decoded).isNotNull();
        assertThat(decoded.createdAt()).isEqualTo(Instant.ofEpochMilli(2000));
        assertThat(decoded.id()).isEqualTo(7L);
        // 🔒 顺序主键不得以明文出现在对外游标里
        assertThat(v.nextCursor()).doesNotContain("7");
    }

    @Test
    void viewEmptyLedgerReturnsZeroItemsNoCursor() {
        when(walletService.balanceOf(1L)).thenReturn(0L);
        when(txns.findPageBefore(anyLong(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        PawCoinWalletView v = service().view(1L, null, 20);

        assertThat(v.balance()).isZero();
        assertThat(v.items()).isEmpty();
        assertThat(v.hasMore()).isFalse();
        assertThat(v.nextCursor()).isNull();
    }
}

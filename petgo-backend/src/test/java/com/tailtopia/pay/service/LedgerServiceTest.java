package com.tailtopia.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.pay.domain.LedgerAccount;
import com.tailtopia.pay.domain.LedgerEntry;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.IdempotencyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：双分录不变式 + 幂等（mock repo/idempotency）。不平衡/空/负额拒绝且不落库；同键重放返回既有组不重复记账。
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    LedgerEntryRepository ledger;
    @Mock
    IdempotencyService idempotency;

    private LedgerService service() {
        return new LedgerService(ledger, idempotency);
    }

    private LedgerLine dr(long amt) {
        return LedgerLine.debit(LedgerAccount.CASH_IN, amt, null, null, null);
    }

    private LedgerLine cr(long amt) {
        return LedgerLine.credit(LedgerAccount.FLOAT_LIABILITY, amt, 7L, null, null);
    }

    @Test
    void rejectsUnbalancedAndDoesNotPersist() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(ledger.findFirstByIdempotencyKey("k")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().post("g", List.of(dr(100), cr(90)), "k"))
                .isInstanceOf(AppException.class);
        verify(ledger, never()).save(any());
    }

    @Test
    void rejectsEmptyAndNonPositive() {
        when(idempotency.findResourceId(anyString())).thenReturn(Optional.empty());
        when(ledger.findFirstByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().post("g", List.of(), "k1")).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service().post("g", List.of(dr(0), cr(0)), "k2"))
                .isInstanceOf(AppException.class);
        verify(ledger, never()).save(any());
    }

    @Test
    void balancedPostsAllLinesAndStoresIdempotency() {
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(ledger.findFirstByIdempotencyKey("k")).thenReturn(Optional.empty());
        when(ledger.save(any(LedgerEntry.class))).thenAnswer(inv -> {
            LedgerEntry e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 1L);
            return e;
        });

        String group = service().post("g-1", List.of(dr(100), cr(100)), "k");

        assertThat(group).isEqualTo("g-1");
        verify(ledger, org.mockito.Mockito.times(2)).save(any(LedgerEntry.class));
        verify(idempotency).store("k", 1L);
    }

    @Test
    void replaysExistingGroupOnIdempotencyHit() {
        LedgerEntry existing = LedgerEntry.of("g-old", LedgerAccount.CASH_IN,
                com.tailtopia.pay.domain.LedgerDirection.DEBIT, 100, null, null, null, "k");
        ReflectionTestUtils.setField(existing, "id", 55L);
        when(idempotency.findResourceId("k")).thenReturn(Optional.of(55L));
        when(ledger.findById(55L)).thenReturn(Optional.of(existing));

        String group = service().post("g-new", List.of(dr(100), cr(100)), "k");

        assertThat(group).isEqualTo("g-old");
        verify(ledger, never()).save(any());
    }

    @Test
    void replaysViaDbWhenBeyondRedisTtl() {
        LedgerEntry existing = LedgerEntry.of("g-db", LedgerAccount.CASH_IN,
                com.tailtopia.pay.domain.LedgerDirection.DEBIT, 100, null, null, null, "k");
        when(idempotency.findResourceId("k")).thenReturn(Optional.empty());
        when(ledger.findFirstByIdempotencyKey("k")).thenReturn(Optional.of(existing));

        assertThat(service().post("g-new", List.of(dr(100), cr(100)), "k")).isEqualTo("g-db");
        verify(ledger, never()).save(any());
    }
}

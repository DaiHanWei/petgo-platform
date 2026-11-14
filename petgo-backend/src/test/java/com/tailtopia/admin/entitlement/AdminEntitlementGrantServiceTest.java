package com.tailtopia.admin.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.entitlement.service.AdminEntitlementGrantService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.GrantResult;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：V1.5.0 gift-pack Story 5-2 · AC2.5 —— 0 与 4 被拒、原因空被拒、ANY 被拒、重放只发一次且只一条审计。 */
class AdminEntitlementGrantServiceTest {

    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";

    private EntitlementCreditService credits;
    private AdminAuditService audit;
    private AdminEntitlementGrantService service;

    @BeforeEach
    void setUp() {
        credits = mock(EntitlementCreditService.class);
        audit = mock(AdminAuditService.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
        User u = mock(User.class);
        when(accounts.findUserById(7L)).thenReturn(Optional.of(u));
        when(deletions.findByUserId(7L)).thenReturn(Optional.empty());
        service = new AdminEntitlementGrantService(credits, accounts, deletions, audit);
    }

    private void rejected(CreditType type, int qty, String reason, String token) {
        assertThatThrownBy(() -> service.grant(7L, type, qty, reason, token, 3L)).isInstanceOf(AppException.class);
    }

    @Test
    void validationRejectsBeforeAnyWrite() {
        rejected(CreditType.TAILSONALITY, 0, "补偿", TOKEN);
        rejected(CreditType.TAILSONALITY, 4, "补偿", TOKEN);
        rejected(CreditType.TAILSONALITY, 1, "   ", TOKEN);
        rejected(CreditType.TAILSONALITY, 1, "x".repeat(201), TOKEN);
        rejected(CreditType.KTP_HD_ANY, 1, "补偿", TOKEN);
        rejected(CreditType.TAILSONALITY, 1, "补偿", "bad token!");
        verify(credits, never()).grant(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void grantsViaEntitlementServiceAndAuditsOnce() {
        when(credits.grant(any())).thenReturn(new GrantResult(1L, Instant.EPOCH, false));
        AdminEntitlementGrantService.Result r = service.grant(7L, CreditType.KTP_HD_SIM, 2, " 客诉补偿 ", TOKEN, 3L);
        assertThat(r.replayed()).isFalse();
        ArgumentCaptor<GrantCommand> c = ArgumentCaptor.forClass(GrantCommand.class);
        verify(credits).grant(c.capture());
        assertThat(c.getValue().source()).isEqualTo(CreditSource.ADMIN);
        assertThat(c.getValue().idemKey()).isEqualTo("ADMIN:" + TOKEN);
        assertThat(c.getValue().sourceRef()).isEqualTo(TOKEN);
        assertThat(c.getValue().operatorAdminId()).isEqualTo(3L);
        assertThat(c.getValue().reason()).isEqualTo("客诉补偿");
        verify(audit, times(1)).record(eq(3L), eq("ENTITLEMENT_ADMIN_GRANT"), eq("USER"), eq("7"),
                eq("KTP_HD:SIM × 2 · 客诉补偿"));
    }

    @Test
    void replayDoesNotAuditAgain() {
        when(credits.grant(any())).thenReturn(new GrantResult(1L, Instant.EPOCH, true));
        assertThat(service.grant(7L, CreditType.GIFT_1K, 3, "补偿", TOKEN, 3L).replayed()).isTrue();
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}

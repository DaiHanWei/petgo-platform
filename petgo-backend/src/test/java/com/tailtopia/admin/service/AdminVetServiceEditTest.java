package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultRatingQueryService;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.service.VetAccountService;
import com.tailtopia.vet.service.VetPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** L0：编辑/重置（Story 2.4 AC2/AC3/AC4/AC5）——写审计、不碰会话/状态、密码不入审计。 */
class AdminVetServiceEditTest {

    private VetAccountService vetAccounts;
    private AdminAuditService audit;
    private ConsultInterruptService interrupt;
    private VetPresenceService presence;
    private AdminVetService service;

    @BeforeEach
    void setUp() {
        vetAccounts = mock(VetAccountService.class);
        audit = mock(AdminAuditService.class);
        interrupt = mock(ConsultInterruptService.class);
        presence = mock(VetPresenceService.class);
        service = new AdminVetService(vetAccounts, mock(ConsultRatingQueryService.class),
                presence, interrupt, mock(TencentImClient.class),
                mock(VetQualificationService.class), audit,
                mock(com.tailtopia.consult.service.ConsultQualityQueryService.class));
    }

    @Test
    void updateProfileAuditsAndDoesNotTouchSessionOrStatus() {
        service.updateProfile(8L, "新名", "new@x", "+62-900", 3L);

        verify(vetAccounts).updateProfile(8L, "新名", "new@x", "+62-900");
        // 不中断会话 / 不改在线态 / 不改封禁状态。
        verify(interrupt, never()).interruptByVetBan(anyLong());
        verify(presence, never()).goOffline(anyLong());
        verify(vetAccounts, never()).setStatus(anyLong(), org.mockito.ArgumentMatchers.any());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(3L), eq(AuditActions.VET_UPDATED), eq("VET_ACCOUNT"),
                eq("8"), summary.capture());
        // summary 含邮箱但不含手机号明文。
        assertThat(summary.getValue()).contains("new@x").doesNotContain("+62-900");
    }

    @Test
    void resetPasswordAuditsWithoutPasswordChars() {
        service.resetPassword(8L, "BrandNew#42", 3L);

        verify(vetAccounts).resetPassword(8L, "BrandNew#42");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(3L), eq(AuditActions.VET_PASSWORD_RESET), eq("VET_ACCOUNT"),
                eq("8"), summary.capture());
        assertThat(summary.getValue()).doesNotContain("BrandNew#42");
        // 重置不触发封禁/中断。
        verify(interrupt, never()).interruptByVetBan(anyLong());
    }
}

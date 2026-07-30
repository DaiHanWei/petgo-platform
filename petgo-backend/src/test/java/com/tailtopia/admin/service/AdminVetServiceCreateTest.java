package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.vetqual.service.VetQualificationService;
import com.tailtopia.consult.service.ConsultInterruptService;
import com.tailtopia.consult.service.ConsultRatingQueryService;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetAccountService;
import com.tailtopia.vet.service.VetPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：建号链路（Story 2.3 AC2/AC3/AC6）——透传手机号、置待完善资质、写 VET_CREATED 审计（不含密码/手机号）。 */
class AdminVetServiceCreateTest {

    private VetAccountService vetAccounts;
    private VetQualificationService vetQual;
    private AdminAuditService audit;
    private AdminVetService service;

    @BeforeEach
    void setUp() {
        vetAccounts = mock(VetAccountService.class);
        vetQual = mock(VetQualificationService.class);
        audit = mock(AdminAuditService.class);
        service = new AdminVetService(vetAccounts, mock(ConsultRatingQueryService.class),
                mock(VetPresenceService.class), mock(ConsultInterruptService.class),
                mock(TencentImClient.class), vetQual, audit,
                mock(com.tailtopia.consult.service.ConsultQualityQueryService.class),
                mock(com.tailtopia.shared.media.AliyunOssClient.class),
                mock(com.tailtopia.shared.media.MediaProperties.class));
    }

    @Test
    void createPassesPhoneEnsuresQualificationAndAuditsWithoutSecrets() {
        VetAccount created = VetAccount.create("dr@x", "{bcrypt}h", "Dr X");
        ReflectionTestUtils.setField(created, "id", 77L);
        when(vetAccounts.create("Dr X", "dr@x", "Secret#123", "+62-811-000"))
                .thenReturn(created);

        long id = service.create("Dr X", "dr@x", "Secret#123", "+62-811-000", 9L);

        assertThat(id).isEqualTo(77L);
        // 透传手机号给账号服务。
        verify(vetAccounts).create("Dr X", "dr@x", "Secret#123", "+62-811-000");
        // 建号后置待完善资质（不可接单）。
        verify(vetQual).ensureForVet(77L);

        // 写 VET_CREATED 审计；summary 不含密码/手机号明文。
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(9L), eq(AuditActions.VET_CREATED), eq("VET_ACCOUNT"),
                eq("77"), summary.capture());
        assertThat(summary.getValue()).contains("Dr X").contains("dr@x")
                .doesNotContain("Secret#123").doesNotContain("+62-811-000");
    }
}

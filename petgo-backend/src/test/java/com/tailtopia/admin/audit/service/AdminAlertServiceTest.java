package com.tailtopia.admin.audit.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.domain.AdminAccountType;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0：安全告警（AC7）——受众取「全体在职超管」，仅记 id/数量不外泄 PII。 */
class AdminAlertServiceTest {

    @Test
    void alertQueriesActiveSuperAdminsAndDoesNotThrow() {
        AdminAccountRepository accounts = mock(AdminAccountRepository.class);
        when(accounts.findByAccountTypeAndStatus(AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE))
                .thenReturn(List.of(
                        AdminAccount.newSuperAdmin("a@x", "A", "{bcrypt}h"),
                        AdminAccount.newSuperAdmin("b@x", "B", "{bcrypt}h")));
        AdminAlertService service = new AdminAlertService(accounts);

        assertThatCode(() -> service.alertSuperAdmins("EMERGENCY_LOGIN_SUCCEEDED", 7L))
                .doesNotThrowAnyException();

        verify(accounts).findByAccountTypeAndStatus(
                AdminAccountType.SUPER_ADMIN, AdminAccountStatus.ACTIVE);
    }
}

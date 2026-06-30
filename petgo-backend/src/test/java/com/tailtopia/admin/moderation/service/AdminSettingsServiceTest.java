package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.domain.AdminSettings;
import com.tailtopia.admin.moderation.repository.AdminSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：单行系统配置读写（Story 4.3）——缺省 false、切换 + 写审计 SETTING_CHANGED。 */
class AdminSettingsServiceTest {

    private AdminSettingsRepository repo;
    private AdminAuditService auditService;
    private AdminSettingsService service;

    @BeforeEach
    void setUp() {
        repo = mock(AdminSettingsRepository.class);
        auditService = mock(AdminAuditService.class);
        service = new AdminSettingsService(repo, auditService);
    }

    @Test
    void defaultsToFalseWhenRowMissing() {
        when(repo.findById(AdminSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThat(service.isManualReviewEnabled()).isFalse();
    }

    @Test
    void readsStoredFlag() {
        AdminSettings s = mock(AdminSettings.class);
        when(s.isManualReviewEnabled()).thenReturn(true);
        when(repo.findById(AdminSettings.SINGLETON_ID)).thenReturn(Optional.of(s));
        assertThat(service.isManualReviewEnabled()).isTrue();
    }

    @Test
    void togglePersistsAndAudits() {
        AdminSettings s = mock(AdminSettings.class);
        when(repo.findById(AdminSettings.SINGLETON_ID)).thenReturn(Optional.of(s));

        service.setManualReviewEnabled(true, 7L);

        verify(s).setManualReviewEnabled(true);
        verify(repo).save(s);
        verify(auditService).record(eq(7L), eq(AuditActions.SETTING_CHANGED), eq("ADMIN_SETTING"),
                eq("manual_review_enabled"), any());
    }
}

package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.DeleteReason;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：内容管理 service（Story 4.2）——下架必填原因、下架/恢复委托 + 审计强制副作用。 */
class AdminContentManageServiceTest {

    private ContentService contentService;
    private AdminAuditService auditService;
    private AdminContentManageService service;

    @BeforeEach
    void setUp() {
        contentService = mock(ContentService.class);
        auditService = mock(AdminAuditService.class);
        service = new AdminContentManageService(contentService, auditService);
    }

    @Test
    void browseDelegatesToContentService() {
        service.browse("DAILY", 7L, null, null, "ONLINE", "猫", 0);
        verify(contentService).adminSearch(eq(ContentType.DAILY), eq(7L), any(), any(),
                eq(Boolean.FALSE), eq("猫"), anyInt(), anyInt());
    }

    @Test
    void takedownRejectsBlankReason() {
        assertThatThrownBy(() -> service.takedown(5L, "  ", 1L)).isInstanceOf(AppException.class);
        verifyNoInteractions(contentService);
        verify(auditService, never()).record(anyLong(), any(), any(), any(), any());
    }

    @Test
    void takedownSoftDeletesAndAudits() {
        service.takedown(5L, "垃圾广告", 1L);
        verify(contentService).softDelete(5L, DeleteReason.ADMIN_TAKEDOWN);
        verify(auditService).record(eq(1L), eq(AuditActions.CONTENT_TAKEN_DOWN), eq("CONTENT_POST"),
                eq("5"), contains("垃圾广告"));
    }

    @Test
    void restoreDelegatesAndAudits() {
        assertThatCode(() -> service.restore(9L, 2L)).doesNotThrowAnyException();
        verify(contentService).restore(9L);
        verify(auditService).record(eq(2L), eq(AuditActions.CONTENT_RESTORED), eq("CONTENT_POST"),
                eq("9"), any());
    }
}

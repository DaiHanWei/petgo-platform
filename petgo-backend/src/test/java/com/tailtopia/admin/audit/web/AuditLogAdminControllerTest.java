package com.tailtopia.admin.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.domain.AdminAuditLog;
import com.tailtopia.admin.audit.service.AdminAuditService;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

/** L0：审计查询控制器（AC5/AC6）——HTMX 片段 vs 整页、日期→UTC 半开区间、空 action→null。 */
class AuditLogAdminControllerTest {

    private AdminAuditService auditService;
    private AuditLogAdminController controller;

    @BeforeEach
    void setUp() {
        auditService = mock(AdminAuditService.class);
        controller = new AuditLogAdminController(auditService);
        when(auditService.search(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.<AdminAuditLog>empty());
    }

    @Test
    void fullViewWhenNotHtmxAndTranslatesDateRange() {
        Model model = new ConcurrentModel();
        String view = controller.auditLogs(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7L, "VET_BANNED", 0, null, model);

        assertThat(view).isEqualTo("admin/audit-logs");

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(auditService).search(from.capture(), to.capture(),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("VET_BANNED"), any(Pageable.class));
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        // to+1 天的 00:00（半开区间，含 6-30 全天）。
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(model.getAttribute("active")).isEqualTo("audit-logs");
        assertThat(model.getAttribute("result")).isNotNull();
        assertThat(model.getAttribute("actionTypes")).isNotNull();
    }

    @Test
    void fragmentViewWhenHtmxRequest() {
        Model model = new ConcurrentModel();
        String view = controller.auditLogs(null, null, null, null, 0, "true", model);
        assertThat(view).isEqualTo("admin/audit-logs :: resultsFragment");
    }

    @Test
    void blankActionAndNoDatesBecomeNullFilters() {
        Model model = new ConcurrentModel();
        controller.auditLogs(null, null, null, "  ", 0, null, model);
        verify(auditService).search(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                any(Pageable.class));
    }
}

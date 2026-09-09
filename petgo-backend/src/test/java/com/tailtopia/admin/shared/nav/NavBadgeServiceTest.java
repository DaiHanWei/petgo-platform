package com.tailtopia.admin.shared.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.moderation.dto.ReviewTab;
import com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService;
import com.tailtopia.admin.moderation.service.TicketsWorkbenchService;
import com.tailtopia.admin.refund.service.AdminRefundQueryService;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * L0：待办角标只汇总登录者可见队列（Story 2.2 AC2，UI 稿 0-2），且与各页 Service 的「待处理」同源
 * （Story 2.9 AC1：A1 四页签之和、A6 三段之和）；空态去向只列其他有待处理的可见队列（AC3）。
 */
class NavBadgeServiceTest {

    private final ManualReviewWorkbenchService manual = mock(ManualReviewWorkbenchService.class);
    private final TicketsWorkbenchService tickets = mock(TicketsWorkbenchService.class);
    private final ConsultAnomalyService anomalies = mock(ConsultAnomalyService.class);
    private final AdminSupportTicketQueryService support = mock(AdminSupportTicketQueryService.class);
    private final AdminRefundQueryService refunds = mock(AdminRefundQueryService.class);
    private final NavBadgeService service = new NavBadgeService(manual, tickets, anomalies, support, refunds);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void stubAll() {
        when(manual.counts(any())).thenReturn(Map.of(ReviewTab.SUBMISSION, 5L, ReviewTab.REPORT, 4L,
                ReviewTab.NAME, 2L, ReviewTab.AVATAR, 1L));
        when(tickets.pendingCount()).thenReturn(3L);
        when(anomalies.count(AnomalyStatus.OPEN)).thenReturn(1L);
        when(support.pendingCount()).thenReturn(4L);
        when(refunds.pendingCount()).thenReturn(2L);
    }

    @Test
    void superAdminGetsAllFiveQueuesAndTotal() {
        stubAll();
        Map<String, Long> c = service.counts(Set.of("ROLE_SUPER_ADMIN"));
        assertThat(c).containsEntry("manual-review", 12L).containsEntry("tickets", 3L).containsEntry("anomalies", 1L)
                .containsEntry("support-tickets", 4L).containsEntry("refunds", 2L).containsEntry("total", 22L);
    }

    @Test
    void staffOnlySeesVisibleQueuesAndTotalSumsOnlyThose() {
        stubAll();
        Map<String, Long> c = service.counts(Set.of("ROLE_ADMIN", AdminPermissions.CONTENT_TAKEDOWN));
        assertThat(c).containsOnlyKeys("manual-review", "total");
        assertThat(c.get("total")).isEqualTo(12L);
        verify(tickets, never()).pendingCount();
        verify(refunds, never()).pendingCount();
    }

    @Test
    void otherQueuesExcludeCurrentAndEmptyOnes() {
        stubAll();
        when(anomalies.count(AnomalyStatus.OPEN)).thenReturn(0L);
        var t = new TestingAuthenticationToken("a", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
        List<NavBadgeService.QueueLink> links = service.otherQueues("tickets");
        assertThat(links).extracting(NavBadgeService.QueueLink::key)
                .containsExactly("manual-review", "support-tickets", "refunds");
        assertThat(links.get(0).route()).isEqualTo("/admin/manual-review");
        assertThat(links.get(0).navKey()).isEqualTo("admin.nav.review");
        assertThat(links.get(0).count()).isEqualTo(12L);
    }
}

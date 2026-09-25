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
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
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
    private final WarmReplyQueueService warmReplies = mock(WarmReplyQueueService.class);
    private final NavBadgeService service = new NavBadgeService(manual, tickets, anomalies, support, refunds, warmReplies);

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
        when(warmReplies.pendingCount()).thenReturn(6L);
    }

    @Test
    void superAdminGetsAllFiveQueuesAndTotal() {
        stubAll();
        Map<String, Long> c = service.counts(Set.of("ROLE_SUPER_ADMIN"));
        assertThat(c).containsEntry("manual-review", 12L).containsEntry("tickets", 3L).containsEntry("anomalies", 1L)
                .containsEntry("support-tickets", 4L).containsEntry("refunds", 2L).containsEntry("warm-replies", 6L)
                .containsEntry("total", 28L);
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

    private void loginAsSuperAdmin() {
        var t = new TestingAuthenticationToken("a", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
        t.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(t);
    }

    /** bug 20260923-554：被举报用户页空态只推荐同组（内容治理）的人工复核，不混入问诊异常 / 客服工单等。 */
    @Test
    void otherQueuesExcludeCurrentAndEmptyOnes() {
        stubAll();
        loginAsSuperAdmin();
        List<NavBadgeService.QueueLink> links = service.otherQueues("tickets");
        assertThat(links).extracting(NavBadgeService.QueueLink::key).containsExactly("manual-review");
        assertThat(links.get(0).route()).isEqualTo("/admin/manual-review");
        assertThat(links.get(0).navKey()).isEqualTo("admin.nav.review");
        assertThat(links.get(0).count()).isEqualTo(12L);
    }

    @Test
    void manualReviewEmptyStateOnlyLinksToReportedUsers() {
        stubAll();
        loginAsSuperAdmin();
        assertThat(service.otherQueues("manual-review")).extracting(NavBadgeService.QueueLink::key)
                .containsExactly("tickets");
    }

    @Test
    void serviceGroupLinksOnlyWithinAnomaliesSupportAndRefunds() {
        stubAll();
        when(anomalies.count(AnomalyStatus.OPEN)).thenReturn(0L);
        loginAsSuperAdmin();
        assertThat(service.otherQueues("support-tickets")).extracting(NavBadgeService.QueueLink::key)
                .as("同组里待处理为 0 的（anomalies）照旧不列").containsExactly("refunds");
        assertThat(service.otherQueues("anomalies")).extracting(NavBadgeService.QueueLink::key)
                .containsExactly("support-tickets", "refunds");
    }

    @Test
    void warmRepliesAndShopPagesHaveNoCrossGroupLinks() {
        stubAll();
        loginAsSuperAdmin();
        assertThat(service.otherQueues("warm-replies")).isEmpty();
        assertThat(service.otherQueues("shop-returns")).isEmpty();
        assertThat(service.otherQueues("shop-order-exceptions")).isEmpty();
    }

    @Test
    void ungroupedKeyKeepsTheOriginalAllQueuesBehaviour() {
        stubAll();
        loginAsSuperAdmin();
        assertThat(service.otherQueues("some-future-page")).extracting(NavBadgeService.QueueLink::key)
                .containsExactly("manual-review", "tickets", "anomalies", "support-tickets", "refunds", "warm-replies");
    }

    @Test
    void sidebarCountsAreNotAffectedByGrouping() {
        stubAll();
        assertThat(service.counts(Set.of("ROLE_SUPER_ADMIN"))).containsOnlyKeys(
                "manual-review", "tickets", "anomalies", "support-tickets", "refunds", "warm-replies", "total");
    }
}

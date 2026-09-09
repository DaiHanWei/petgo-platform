package com.tailtopia.admin.shared.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import java.util.List;

/** L0：待办角标只汇总登录者可见队列（Story 2.2 AC2，UI 稿 0-2）。 */
class NavBadgeServiceTest {

    private final ManualReviewItemRepository manual = mock(ManualReviewItemRepository.class);
    private final UnifiedTicketQueryService tickets = mock(UnifiedTicketQueryService.class);
    private final ConsultAnomalyRepository anomalies = mock(ConsultAnomalyRepository.class);
    private final FeedbackTicketRepository support = mock(FeedbackTicketRepository.class);
    private final RefundRequestRepository refunds = mock(RefundRequestRepository.class);
    private final NavBadgeService service = new NavBadgeService(manual, tickets, anomalies, support, refunds);

    @Test
    void superAdminGetsAllFiveQueuesAndTotal() {
        when(manual.countByStatus(ReviewStatus.PENDING)).thenReturn(12L);
        when(tickets.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 1), 3));
        when(anomalies.countByStatus(AnomalyStatus.OPEN)).thenReturn(1L);
        when(support.countByStatusIn(any())).thenReturn(4L);
        when(refunds.countByApprovalStatusIn(any())).thenReturn(2L);

        Map<String, Long> c = service.counts(Set.of("ROLE_SUPER_ADMIN"));

        assertThat(c).containsEntry("manual-review", 12L).containsEntry("tickets", 3L).containsEntry("anomalies", 1L)
                .containsEntry("support-tickets", 4L).containsEntry("refunds", 2L).containsEntry("total", 22L);
    }

    @Test
    void staffOnlySeesVisibleQueuesAndTotalSumsOnlyThose() {
        when(manual.countByStatus(ReviewStatus.PENDING)).thenReturn(12L);
        Map<String, Long> c = service.counts(Set.of("ROLE_ADMIN", AdminPermissions.CONTENT_TAKEDOWN));
        assertThat(c).containsOnlyKeys("manual-review", "total");
        assertThat(c.get("total")).isEqualTo(12L);
        verify(tickets, never()).search(any(), any(), any(), any());
        verify(refunds, never()).countByApprovalStatusIn(any());
    }
}

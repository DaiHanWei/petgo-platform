package com.tailtopia.admin.shared.nav;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.repository.ConsultAnomalyRepository;
import com.tailtopia.admin.moderation.domain.ReviewStatus;
import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.admin.shared.AdminPageCatalog;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.support.domain.TicketStatus;
import com.tailtopia.support.repository.FeedbackTicketRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待办中心角标计数（V1.3.0 Story 2.2 AC2）。五个队列各取现有页面的「待处理」口径；
 * 只汇总<b>登录者可见</b>的队列（UI 稿 0-2）；暖贴跟进（Story 4.4）与场所举报（5.4）后续接入同一聚合。
 */
@Service
public class NavBadgeService {

    /** 队列 key = catalog 页面 key（也是模板里 data-badge / id 的后缀）。 */
    public static final List<String> QUEUES = List.of(
            "manual-review", "tickets", "anomalies", "support-tickets", "refunds");

    private final ManualReviewItemRepository manualReview;
    private final UnifiedTicketQueryService tickets;
    private final ConsultAnomalyRepository anomalies;
    private final FeedbackTicketRepository supportTickets;
    private final RefundRequestRepository refunds;

    public NavBadgeService(ManualReviewItemRepository manualReview, UnifiedTicketQueryService tickets,
            ConsultAnomalyRepository anomalies, FeedbackTicketRepository supportTickets,
            RefundRequestRepository refunds) {
        this.manualReview = manualReview;
        this.tickets = tickets;
        this.anomalies = anomalies;
        this.supportTickets = supportTickets;
        this.refunds = refunds;
    }

    /** 队列 key → 待处理数（仅可见队列）；另含 {@code total}。 */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(Set<String> authorities) {
        Map<String, Long> out = new LinkedHashMap<>();
        long total = 0;
        for (String q : QUEUES) {
            AdminPageCatalog.Page page = AdminPageCatalog.PAGES.stream()
                    .filter(p -> p.key().equals(q)).findFirst().orElse(null);
            if (page == null || !AdminNavModel.visible(page, authorities)) {
                continue;
            }
            long n = supplierFor(q).getAsLong();
            out.put(q, n);
            total += n;
        }
        out.put("total", total);
        return out;
    }

    private LongSupplier supplierFor(String queue) {
        return switch (queue) {
            case "manual-review" -> () -> manualReview.countByStatus(ReviewStatus.PENDING);
            case "tickets" -> () -> tickets.search(null, TicketStatusBucket.PENDING, null, PageRequest.of(0, 1))
                    .getTotalElements();
            case "anomalies" -> () -> anomalies.countByStatus(AnomalyStatus.OPEN);
            case "support-tickets" -> () -> supportTickets.countByStatusIn(
                    List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));
            // 退款：待审批 + 已审批待打款都算「待处理」（三段流未走完）。
            case "refunds" -> () -> refunds.countByApprovalStatusIn(
                    List.of(ApprovalStatus.PENDING_APPROVAL, ApprovalStatus.APPROVED));
            default -> () -> 0L;
        };
    }
}

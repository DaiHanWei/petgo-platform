package com.tailtopia.admin.shared.nav;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.moderation.dto.ReviewFilters;
import com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService;
import com.tailtopia.admin.moderation.service.TicketsWorkbenchService;
import com.tailtopia.admin.refund.service.AdminRefundQueryService;
import com.tailtopia.admin.shared.AdminPageCatalog;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待办中心角标计数（V1.3.0 Story 2.2 AC2 → Story 2.9 AC1 收口）。
 * <b>计数三处同源</b>：侧栏组角标 / 组内各项 / 页内页签计数都来自各页 Service 的同一「待处理」口径
 * （A1 四页签之和、A2 待处置、A4 OPEN、A5 待处理、A6 判定 + 审批 + 打款三段之和、A9 暖贴待跟进），不再各查一套。
 * 只汇总<b>登录者可见</b>的队列（UI 稿 0-2）；场所举报（Story 5.4）作为 A1 第五页签 {@code PLACE} 走同一聚合（{@code counts} 按 ReviewTab 求和），不另查。
 */
@Service
public class NavBadgeService {

    /** 队列 key = catalog 页面 key（也是模板里 data-badge / id 的后缀）。 */
    public static final List<String> QUEUES = List.of(
            "manual-review", "tickets", "anomalies", "support-tickets", "refunds", "warm-replies");

    /**
     * 空态去向的<b>关联组</b>（bug 20260923-554）：空态只推荐与当前页同一职责的队列，不再把全部队列混着列。
     * <ul>
     * <li>内容治理：人工复核 ↔ 被举报用户；</li>
     * <li>问诊 / 客服 / 退款：问诊异常 ↔ 客服工单 ↔ 退款；</li>
     * <li>暖贴跟进：单独一组；</li>
     * <li>商城售后：退货审核 ↔ 异常订单（二者目前都不在角标队列里，故空态不带去向）。</li>
     * </ul>
     * ⚠️ 只影响空态去向；侧栏角标 {@link #counts} 不受分组影响。不在任何组里的 key 保持原行为（列出全部其他队列）。
     */
    static final List<Set<String>> RELATED_GROUPS = List.of(
            Set.of("manual-review", "tickets"),
            Set.of("anomalies", "support-tickets", "refunds"),
            Set.of("warm-replies"),
            Set.of("shop-returns", "shop-order-exceptions"));

    /** 空态「其他队列还有 N 条」的去向链接（Story 2.9 AC3）。 */
    public record QueueLink(String key, String route, String navKey, long count) {
    }

    private final ManualReviewWorkbenchService manualReview;
    private final TicketsWorkbenchService tickets;
    private final ConsultAnomalyService anomalies;
    private final AdminSupportTicketQueryService supportTickets;
    private final AdminRefundQueryService refunds;
    private final WarmReplyQueueService warmReplies;

    public NavBadgeService(ManualReviewWorkbenchService manualReview, TicketsWorkbenchService tickets,
            ConsultAnomalyService anomalies, AdminSupportTicketQueryService supportTickets,
            AdminRefundQueryService refunds, WarmReplyQueueService warmReplies) {
        this.manualReview = manualReview;
        this.tickets = tickets;
        this.anomalies = anomalies;
        this.supportTickets = supportTickets;
        this.refunds = refunds;
        this.warmReplies = warmReplies;
    }

    /** 队列 key → 待处理数（仅可见队列）；另含 {@code total}。 */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(Set<String> authorities) {
        Map<String, Long> out = new LinkedHashMap<>();
        long total = 0;
        for (String q : QUEUES) {
            AdminPageCatalog.Page page = pageOf(q);
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

    /**
     * 当前登录者可见的<b>其他</b>队列去向（待处理 &gt; 0），供五页空态「其他队列还有 N 条 →」（AC3，与角标同一口径）；
     * 只列与当前页同一关联组（{@link #RELATED_GROUPS}）的队列。
     * 登录态从 {@link SecurityContextHolder} 取；由空态片段 {@code tpl-a-empty} 按需调用（只在页签清空时才查，Controller 不预算）。
     */
    @Transactional(readOnly = true)
    public List<QueueLink> otherQueues(String currentKey) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth == null ? Set.of()
                : auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        Set<String> related = RELATED_GROUPS.stream().filter(g -> g.contains(currentKey)).findFirst().orElse(null);
        List<QueueLink> out = new ArrayList<>();
        counts(authorities).forEach((key, n) -> {
            if ("total".equals(key) || key.equals(currentKey) || n <= 0) {
                return;
            }
            if (related != null && !related.contains(key)) {
                return; // bug 554：不同职责的队列不进本页空态
            }
            AdminPageCatalog.Page page = pageOf(key);
            if (page != null) {
                out.add(new QueueLink(key, page.route(), page.navKey(), n));
            }
        });
        return out;
    }

    private static AdminPageCatalog.Page pageOf(String key) {
        return AdminPageCatalog.PAGES.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    private LongSupplier supplierFor(String queue) {
        return switch (queue) {
            // A1：五页签待处理之和（送审 + 内容举报 + 场所举报（5.4）+ 名称 + 头像）
            case "manual-review" -> () -> manualReview.counts(ReviewFilters.DEFAULT).values().stream()
                    .mapToLong(Long::longValue).sum();
            case "tickets" -> tickets::pendingCount;
            case "anomalies" -> () -> anomalies.count(AnomalyStatus.OPEN);
            case "support-tickets" -> supportTickets::pendingCount;
            // A6：判定 + 审批 + 打款三段未走完都算「待处理」（与页签同口径）
            case "refunds" -> refunds::pendingCount;
            // A9（Story 4.4）：暖贴回复跟进待处理数
            case "warm-replies" -> warmReplies::pendingCount;
            default -> () -> 0L;
        };
    }
}

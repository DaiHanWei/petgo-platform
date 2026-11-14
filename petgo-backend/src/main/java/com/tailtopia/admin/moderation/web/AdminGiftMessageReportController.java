package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.moderation.dto.ReviewTab;
import com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.gift.service.GiftMessageReportService;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 统一复核工作台「祝福语举报」页签的两个处置动作（V1.5.0 gift-pack Story 1-5 · D-8）。照场所举报
 * （{@code AdminPlaceController.delist / dismissReports} 的 from=review 分支）：成功后回 {@code review-done :: done}
 * （下一条 + 删行 + 页签计数 + toast + 角标刷新）。权限沿用内容举报处置 {@code content.takedown}。
 * 写入经 {@link GiftMessageReportService}，审计与写入同一事务；摘要只记条数，不记祝福语正文。
 */
@Controller
public class AdminGiftMessageReportController {

    static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')";
    private static final String ROUTE = "/admin/gift-messages";

    private final GiftMessageReportService reports;
    private final AdminAuditService audit;
    private final ManualReviewWorkbenchService reviewWorkbench;
    private final Messages msg;

    public AdminGiftMessageReportController(GiftMessageReportService reports, AdminAuditService audit,
            ManualReviewWorkbenchService reviewWorkbench, Messages msg) {
        this.reports = reports;
        this.audit = audit;
        this.reviewWorkbench = reviewWorkbench;
        this.msg = msg;
    }

    /** 隐藏祝福语：该订单全部 PENDING → ACTIONED + {@code message_hidden_at}；审计 {@code GIFT_MESSAGE_HIDDEN}。 */
    @PostMapping(ROUTE + "/{id:\\d+}/hide")
    @PreAuthorize(AUTH)
    @Transactional
    public String hide(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, HxRequest hx,
            Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/manual-review?tab=gift";
        }
        int n = reports.hide(id, admin.getAdminAccountId());
        audit.record(admin.getAdminAccountId(), AuditActions.GIFT_MESSAGE_HIDDEN, "gift_orders", String.valueOf(id),
                "actioned=" + n);
        return done(id, msg.get("admin.v150.giftMessage.flash.hidden"), hx, model, response);
    }

    /** 驳回：全部 PENDING → DISMISSED；审计 {@code GIFT_MESSAGE_REPORT_DISMISSED}。 */
    @PostMapping(ROUTE + "/{id:\\d+}/reports/dismiss-all")
    @PreAuthorize(AUTH)
    @Transactional
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, HxRequest hx,
            Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/manual-review?tab=gift";
        }
        int n = reports.dismiss(id, admin.getAdminAccountId());
        audit.record(admin.getAdminAccountId(), AuditActions.GIFT_MESSAGE_REPORT_DISMISSED, "gift_orders",
                String.valueOf(id), "dismissed=" + n);
        return done(id, msg.get("admin.v150.giftMessage.flash.dismissed", n), hx, model, response);
    }

    private String done(long orderId, String message, HxRequest hx, Model model, HttpServletResponse response) {
        model.addAttribute("done", reviewWorkbench.afterDispose(hx.currentUrl(), ReviewTab.GIFT, orderId));
        model.addAttribute("message", message);
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/review-done :: done";
    }
}

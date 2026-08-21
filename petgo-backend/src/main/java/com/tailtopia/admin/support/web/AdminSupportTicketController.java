package com.tailtopia.admin.support.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.admin.support.service.AdminTicketRefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.service.SupportTicketService;
import com.tailtopia.shared.i18n.Messages;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台客服工单管理（Story 4.7，FR-52）。Thymeleaf admin slice，{@code /admin/support-tickets/**}，redirect+flash，
 * **不返 JSON**。门控 {@code support.handle}（SUPER_ADMIN 隐式全权）。结案后果（结案/CSAT 通知）由 {@link SupportTicketService}。
 *
 * <p>AB-5B 退款判定（bug 20260728-384/388）：补挂关联订单走 {@code support.handle}；
 * 批准/驳回退款需求属客服 need 判定，与 {@code AdminRefundController} 同权 {@code refund.submit}。
 */
@Controller
public class AdminSupportTicketController {

    private static final int PAGE_SIZE = 20;

    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('support.handle')";
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('support.view') or hasAuthority('support.handle')";
    private static final String REFUND_SUBMIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";

    private final AdminSupportTicketQueryService query;
    private final SupportTicketService ticketService;
    private final AdminTicketRefundService ticketRefund;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminSupportTicketController(AdminSupportTicketQueryService query,
            SupportTicketService ticketService, AdminTicketRefundService ticketRefund,
            Messages msg) {
        this.query = query;
        this.ticketService = ticketService;
        this.ticketRefund = ticketRefund;
        this.msg = msg;
    }

    @GetMapping("/admin/support-tickets")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model,
            @RequestParam(value = "page", defaultValue = "0") int page) {
        var pageResult = query.list(PageRequest.of(Math.max(page, 0), PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "createdAt")));
        model.addAttribute("active", "support-tickets");
        model.addAttribute("items", pageResult.getContent());
        model.addAttribute("page", pageResult);
        return "admin/support-tickets";
    }

    @GetMapping("/admin/support-tickets/{ticketToken}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String ticketToken, Model model, Authentication auth) {
        model.addAttribute("active", "support-tickets");
        // PII 最小可见面（finding #13）：联系方式原文仅 handle/SUPER_ADMIN；view-only 看脱敏值。
        model.addAttribute("ticket", query.find(ticketToken, canSeeContactPii(auth)));
        return "admin/support-ticket-detail";
    }

    private static boolean canSeeContactPii(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || "support.handle".equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** 结案（客服勾「已联系+已解决」）→ RESOLVED + 发结案/CSAT 通知。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, RedirectAttributes flash) {
        try {
            ticketService.resolveTicket(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.resolved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }

    // ===== AB-5B 退款判定（bug 20260728-384/388）=====

    /** 补挂关联订单（按订单 token；归属校验在 service）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/link-order")
    @PreAuthorize(HANDLE_AUTH)
    public String linkOrder(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, @RequestParam("orderToken") String orderToken,
            RedirectAttributes flash) {
        try {
            ticketRefund.linkOrder(ticketToken, orderToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.orderLinked"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }

    /** 批准退款需求：建退款单（如无）+ need→APPROVED，订单进 REFUNDING，App 解锁「选退款方式」（不发通知，AB-5B）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/refund-approve")
    @PreAuthorize(REFUND_SUBMIT_AUTH)
    public String approveRefundNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, RedirectAttributes flash) {
        try {
            ticketRefund.approveRefundNeed(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.refundApproved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }

    /** 驳回退款需求：建退款单留痕（如无）+ need→REJECTED + 发用户通知（A-2 不撒谎）。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/refund-reject")
    @PreAuthorize(REFUND_SUBMIT_AUTH)
    public String rejectRefundNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, RedirectAttributes flash) {
        try {
            ticketRefund.rejectRefundNeed(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.ticket.refundRejected"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }
}

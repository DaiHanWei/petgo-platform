package com.tailtopia.admin.support.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.support.service.AdminSupportTicketQueryService;
import com.tailtopia.admin.support.service.AdminTicketRefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.service.SupportTicketService;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('support.handle')";
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('support.view') or hasAuthority('support.handle')";
    private static final String REFUND_SUBMIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";

    private final AdminSupportTicketQueryService query;
    private final SupportTicketService ticketService;
    private final AdminTicketRefundService ticketRefund;

    public AdminSupportTicketController(AdminSupportTicketQueryService query,
            SupportTicketService ticketService, AdminTicketRefundService ticketRefund) {
        this.query = query;
        this.ticketService = ticketService;
        this.ticketRefund = ticketRefund;
    }

    @GetMapping("/admin/support-tickets")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "support-tickets");
        model.addAttribute("items", query.list());
        return "admin/support-tickets";
    }

    @GetMapping("/admin/support-tickets/{ticketToken}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String ticketToken, Model model) {
        model.addAttribute("active", "support-tickets");
        model.addAttribute("ticket", query.find(ticketToken));
        return "admin/support-ticket-detail";
    }

    /** 结案（客服勾「已联系+已解决」）→ RESOLVED + 发结案/CSAT 通知。 */
    @PostMapping("/admin/support-tickets/{ticketToken}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String ticketToken, RedirectAttributes flash) {
        try {
            ticketService.resolveTicket(ticketToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已结案（已通知用户并邀请 CSAT 评价；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
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
            flash.addFlashAttribute("notice", "已关联订单（操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
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
            flash.addFlashAttribute("notice", "已批准退款需求（订单进入退款流程，用户可在 App 选择退款方式；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
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
            flash.addFlashAttribute("notice", "已驳回退款需求（已通知用户；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/support-tickets/" + ticketToken;
    }
}

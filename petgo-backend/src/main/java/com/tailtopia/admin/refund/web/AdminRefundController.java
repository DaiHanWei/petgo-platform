package com.tailtopia.admin.refund.web;

import com.tailtopia.admin.refund.service.AdminRefundQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
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
 * 后台退款管理（Story 4.4 客服判定 + Story 4.6 主管审批/财务打款）。Thymeleaf admin slice，{@code /admin/refunds/**}，
 * redirect+flash，**不返 JSON**。三段职责分离门控（A-1）：客服 {@code refund.submit}（need 判定，4-4）/
 * 主管 {@code refund.approve}（审批通过/驳回，4-6）/财务 {@code refund.payout}（Iris 打款，4-6）；{@code SUPER_ADMIN} 隐式全权。
 * 各段后果编排（订单联动 + ledger + 通知 + disburse）在 {@link RefundService}。
 */
@Controller
public class AdminRefundController {

    private static final String SUBMIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";
    private static final String APPROVE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.approve')";
    private static final String PAYOUT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.payout')";
    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') "
            + "or hasAuthority('refund.submit') or hasAuthority('refund.approve') or hasAuthority('refund.payout')";

    private final RefundService refundService;
    private final AdminRefundQueryService query;

    public AdminRefundController(RefundService refundService, AdminRefundQueryService query) {
        this.refundService = refundService;
        this.query = query;
    }

    // ===== 列表 / 详情（Story 4.6）=====

    @GetMapping("/admin/refunds")
    @PreAuthorize(VIEW_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "refunds");
        model.addAttribute("items", query.list());
        return "admin/refunds";
    }

    @GetMapping("/admin/refunds/{refundToken}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String refundToken, Model model) {
        model.addAttribute("active", "refunds");
        model.addAttribute("refund", query.find(refundToken));
        return "admin/refund-detail";
    }

    // ===== 客服 need 判定（Story 4.4）=====

    @PostMapping("/admin/refunds/{refundToken}/approve")
    @PreAuthorize(SUBMIT_AUTH)
    public String approveNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, RedirectAttributes flash) {
        try {
            refundService.approveNeed(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已批准退款需求（订单进入退款流程，用户可选退款方式；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds/" + refundToken;
    }

    @PostMapping("/admin/refunds/{refundToken}/reject")
    @PreAuthorize(SUBMIT_AUTH)
    public String rejectNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, RedirectAttributes flash) {
        try {
            refundService.rejectNeed(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已驳回退款需求（订单回落已完成，已通知用户；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds/" + refundToken;
    }

    // ===== 主管审批（Story 4.6）=====

    @PostMapping("/admin/refunds/{refundToken}/approval")
    @PreAuthorize(APPROVE_AUTH)
    public String approveRefund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, @RequestParam("note") String note, RedirectAttributes flash) {
        try {
            refundService.approveRefund(refundToken, admin.getAdminAccountId(), note);
            flash.addFlashAttribute("notice", "已审批通过（待财务打款；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds/" + refundToken;
    }

    @PostMapping("/admin/refunds/{refundToken}/approval-reject")
    @PreAuthorize(APPROVE_AUTH)
    public String rejectRefund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, @RequestParam("reason") String reason, RedirectAttributes flash) {
        try {
            refundService.rejectRefund(refundToken, admin.getAdminAccountId(), reason);
            flash.addFlashAttribute("notice", "已驳回退款申请（订单回落已完成，已通知用户；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds/" + refundToken;
    }

    // ===== 财务 Iris 打款（Story 4.6）=====

    @PostMapping("/admin/refunds/{refundToken}/payout")
    @PreAuthorize(PAYOUT_AUTH)
    public String payout(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, RedirectAttributes flash) {
        try {
            refundService.payoutRefund(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已完成打款（订单已退款，账目已记；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds/" + refundToken;
    }
}

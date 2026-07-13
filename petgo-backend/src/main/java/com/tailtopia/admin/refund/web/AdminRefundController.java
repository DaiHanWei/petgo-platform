package com.tailtopia.admin.refund.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台客服退款需求判定（Story 4.4，AB-5B/A-2）。Thymeleaf admin slice，{@code /admin/refunds/**}，redirect+flash，**不返 JSON**。
 * 门控：{@code refund.submit}（客服提交人）；{@code SUPER_ADMIN} 隐式全权。判定后果（订单联动 + 通知）由 {@link RefundService} 编排。
 *
 * <p>批准 → 订单 {@code COMPLETED→REFUNDING} + 解锁选方式（<b>不发通知</b>，AB-5B）；
 * 驳回 → 订单回落 {@code COMPLETED+refund_rejected} + 发 {@code REFUND_REJECTED} 通知（A-2）。
 * 重复判定（非 PENDING）→ service 抛 409，转 flash 错误提示（照 {@code AdminContentManageController} 范式）。
 */
@Controller
public class AdminRefundController {

    private static final String SUBMIT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";

    private final RefundService refundService;

    public AdminRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping("/admin/refunds/{refundToken}/approve")
    @PreAuthorize(SUBMIT_AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, RedirectAttributes flash) {
        try {
            refundService.approveNeed(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已批准退款需求（订单进入退款流程，用户可选退款方式；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds";
    }

    @PostMapping("/admin/refunds/{refundToken}/reject")
    @PreAuthorize(SUBMIT_AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, RedirectAttributes flash) {
        try {
            refundService.rejectNeed(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已驳回退款需求（订单回落已完成，已通知用户；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/refunds";
    }
}

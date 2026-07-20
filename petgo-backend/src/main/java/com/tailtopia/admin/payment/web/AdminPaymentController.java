package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import com.tailtopia.admin.paysim.service.AdminPaySimulatorService;
import com.tailtopia.admin.service.AdminUserDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
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
 * 后台支付记录通用查询（Story 9.6，AB-8E）。Thymeleaf admin slice，{@code /admin/payments}。
 * 门控 {@code payment.view}（SUPER_ADMIN 隐式全权）。按 userId 跨类型只读查。
 */
@Controller
public class AdminPaymentController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('payment.view')";

    /** 默认视图每页条数。 */
    private static final int PAGE_SIZE = 20;

    private final AdminPaymentQueryService service;

    // ⚠️ stag 专用：仅 stag 分支注入，绝不合并回 v1.1-dev / main。
    private final AdminPaySimulatorService simulator;
    private final boolean simulatorEnabled;

    public AdminPaymentController(AdminPaymentQueryService service, AdminPaySimulatorService simulator,
            @Value("${petgo.pay.simulator-enabled:false}") boolean simulatorEnabled) {
        this.service = service;
        this.simulator = simulator;
        this.simulatorEnabled = simulatorEnabled;
    }

    @GetMapping("/admin/payments")
    @PreAuthorize(VIEW_AUTH)
    public String search(@RequestParam(required = false) Long userId,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("active", "payments");
        model.addAttribute("userId", userId);
        model.addAttribute("simulatorEnabled", simulatorEnabled);
        if (userId == null) {
            Page<AdminPaymentRow> result = service.recent(Math.max(page, 0), PAGE_SIZE);
            model.addAttribute("payments", result.getContent());
            model.addAttribute("page", result.getNumber());
            model.addAttribute("totalPages", result.getTotalPages());
            model.addAttribute("totalElements", result.getTotalElements());
            model.addAttribute("hasPrev", result.hasPrevious());
            model.addAttribute("hasNext", result.hasNext());
        } else {
            model.addAttribute("payments", service.byUser(userId));
        }
        return "admin/payments";
    }

    /**
     * ⚠️ stag 专用：手动模拟支付回调，把订单推向 成功/失败/过时。<b>绝不合并回主线</b>。
     * 运行时靠 {@code petgo.pay.simulator-enabled} flag 门控（prod 不开则拒绝）。
     */
    @PostMapping("/admin/payments/{publicToken}/simulate")
    @PreAuthorize(VIEW_AUTH)
    public String simulate(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String publicToken, @RequestParam AdminPaySimulatorService.Target target,
            RedirectAttributes flash) {
        if (!simulatorEnabled) {
            flash.addFlashAttribute("error", "支付模拟器未启用（仅 stag 环境开放）");
            return "redirect:/admin/payments";
        }
        try {
            flash.addFlashAttribute("notice", simulator.simulate(publicToken, target, admin.getAdminAccountId()));
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/payments";
    }
}

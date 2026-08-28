package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import com.tailtopia.admin.paysim.service.AdminPaySimulatorService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
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
                         @RequestParam(required = false) String purpose,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("active", "payments");
        model.addAttribute("userId", userId);
        // ⚠️ stag 专用：模板据此决定要不要渲染「模拟支付」那一列（prod 不开则整列不出现）。
        model.addAttribute("simulatorEnabled", simulatorEnabled);
        model.addAttribute("purpose", purpose);
        model.addAttribute("status", status);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("purposeOptions", PaymentPurpose.values());
        model.addAttribute("statusOptions", PaymentStatus.values());

        // ⚠️ 用途/状态**宽松解析**：值不认识就当"不限"，不报错。
        //    这两个值只能从下拉里来，出现非法值只可能是有人手改了 URL ——
        //    为此给运营一个报错页不划算，何况"不限"是最无害的退化。
        var filter = new AdminPaymentQueryService.Filter(
                userId, parseEnum(PaymentPurpose.class, purpose),
                parseEnum(PaymentStatus.class, status), from, to);

        Page<AdminPaymentRow> result = service.search(filter, Math.max(page, 0), PAGE_SIZE);
        model.addAttribute("payments", result.getContent());
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("totalElements", result.getTotalElements());
        model.addAttribute("hasPrev", result.hasPrevious());
        model.addAttribute("hasNext", result.hasNext());
        // 🔴 汇总覆盖**整个筛选结果**，不是当前这一页（见 summarize 的说明）。
        model.addAttribute("summary", service.summarize(filter));
        return "admin/payments";
    }

    /** 宽松解析：空 / 不认识 → null（= 不限），绝不抛。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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

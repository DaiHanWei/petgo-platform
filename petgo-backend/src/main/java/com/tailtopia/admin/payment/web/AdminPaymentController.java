package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.payment.dto.AdminPaymentRow;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

    public AdminPaymentController(AdminPaymentQueryService service) {
        this.service = service;
    }

    @GetMapping("/admin/payments")
    @PreAuthorize(VIEW_AUTH)
    public String search(@RequestParam(required = false) Long userId,
                         @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("active", "payments");
        model.addAttribute("userId", userId);
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
}

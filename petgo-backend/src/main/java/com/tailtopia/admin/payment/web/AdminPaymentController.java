package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
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

    private final AdminPaymentQueryService service;

    public AdminPaymentController(AdminPaymentQueryService service) {
        this.service = service;
    }

    @GetMapping("/admin/payments")
    @PreAuthorize(VIEW_AUTH)
    public String search(@RequestParam(required = false) Long userId, Model model) {
        model.addAttribute("active", "payments");
        model.addAttribute("userId", userId);
        model.addAttribute("payments", userId == null ? null : service.byUser(userId));
        return "admin/payments";
    }
}

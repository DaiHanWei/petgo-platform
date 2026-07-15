package com.tailtopia.admin.risk.web;

import com.tailtopia.admin.risk.service.RedOverageMonitorService;
import com.tailtopia.admin.service.AdminUserDetails;
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
 * 后台红色超额只读监控（Story 9.6，AB-7A）。Thymeleaf admin slice，{@code /admin/red-overage}。
 * 门控 {@code risk.view}（看 + 标记，内部注记低危同门控）。<b>纯观测 + 人工标记，无自动拦截</b>。
 */
@Controller
public class AdminRedOverageController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('risk.view')";

    private final RedOverageMonitorService service;

    public AdminRedOverageController(RedOverageMonitorService service) {
        this.service = service;
    }

    @GetMapping("/admin/red-overage")
    @PreAuthorize(AUTH)
    public String list(Model model) {
        model.addAttribute("active", "red-overage");
        model.addAttribute("rows", service.list());
        return "admin/red-overage";
    }

    @PostMapping("/admin/red-overage/{userId}/review")
    @PreAuthorize(AUTH)
    public String review(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam String status, @RequestParam(required = false) String note,
            RedirectAttributes flash) {
        try {
            service.mark(userId, status, note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已更新复核标记（纯注记，无自动处置；操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/red-overage";
    }
}

package com.tailtopia.admin.risk.web;

import com.tailtopia.admin.risk.service.RedOverageMonitorService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('risk.edit')";

    private final RedOverageMonitorService service;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminRedOverageController(RedOverageMonitorService service,
            Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    @GetMapping("/admin/red-overage")
    @PreAuthorize(AUTH)
    public String list(Model model) {
        model.addAttribute("active", "red-overage");
        model.addAttribute("rows", service.list());
        return "admin/red-overage";
    }

    @PostMapping("/admin/red-overage/{userId}/review")
    @PreAuthorize(EDIT_AUTH)
    public String review(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long userId,
            @RequestParam String status, @RequestParam(required = false) String note,
            RedirectAttributes flash) {
        try {
            service.mark(userId, status, note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.redOverage.marked"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/red-overage";
    }
}

package com.tailtopia.admin.virtual.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.virtual.service.AdminSeedBatchService;
import com.tailtopia.admin.virtual.service.AdminSeedBatchService.BatchResult;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 轻量批量种子发布（Story 9.8 Part 2，AB-1.1-02）。Thymeleaf admin slice，{@code /admin/seed-batch}。
 * 门控 {@code virtual_account.manage}。选虚拟账号 + 多行文本逐条发 DAILY，内容 hash 跨批去重。
 */
@Controller
public class AdminSeedBatchController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    private final AdminSeedBatchService batch;
    private final AdminVirtualAccountService accounts;

    public AdminSeedBatchController(AdminSeedBatchService batch, AdminVirtualAccountService accounts) {
        this.batch = batch;
        this.accounts = accounts;
    }

    @GetMapping("/admin/seed-batch")
    @PreAuthorize(AUTH)
    public String form(Model model) {
        model.addAttribute("active", "seed-batch");
        model.addAttribute("accounts", accounts.list());
        return "admin/seed-batch";
    }

    @PostMapping("/admin/seed-batch")
    @PreAuthorize(AUTH)
    public String publish(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam long virtualUserId, @RequestParam String lines, RedirectAttributes flash) {
        try {
            BatchResult r = batch.publishBatch(virtualUserId, lines, admin.getAdminAccountId());
            flash.addFlashAttribute("notice",
                    "批量完成：发布 " + r.published() + " 条，去重跳过 " + r.skipped() + " 条");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/seed-batch";
    }
}

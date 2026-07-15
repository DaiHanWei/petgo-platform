package com.tailtopia.admin.comment.web;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台评论内容管理（Story 9.9）。Thymeleaf admin slice，{@code /admin/comments/**}，redirect+flash。
 * 门控：浏览/下架 {@code content.proactive_takedown}；恢复 {@code content.restore}（复用 9-1 既有码）。
 */
@Controller
public class AdminCommentModerationController {

    private static final String TAKEDOWN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";
    private static final String RESTORE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.restore')";

    private final AdminCommentModerationService service;

    public AdminCommentModerationController(AdminCommentModerationService service) {
        this.service = service;
    }

    @GetMapping("/admin/comments")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String list(Model model) {
        model.addAttribute("active", "comments");
        model.addAttribute("comments", service.recent());
        return "admin/comments";
    }

    @PostMapping("/admin/comments/{id}/takedown")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            service.takedown(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已下架该评论（公开口径移除，操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/comments";
    }

    @PostMapping("/admin/comments/{id}/restore")
    @PreAuthorize(RESTORE_AUTH)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        try {
            service.restore(id, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已恢复该评论");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/comments";
    }
}

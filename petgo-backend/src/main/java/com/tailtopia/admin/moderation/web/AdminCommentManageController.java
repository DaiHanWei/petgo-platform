package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.service.AdminCommentManageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台评论巡查下架 / 恢复（内容审核 story 3，FR-55A，AB-3B 评论扩展）。SSR + HTMX，不返 JSON。
 * 门控沿用 AB-3B：下架 {@code content.proactive_takedown}；恢复 {@code content.restore}；{@code SUPER_ADMIN} 全权。
 * 评论浏览/搜索的完整 UI 属 story 8；本 story 最小交付「能对指定评论 id 下架/恢复」+ 后端逻辑闭环。
 */
@Controller
public class AdminCommentManageController {

    private static final String TAKEDOWN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";
    private static final String RESTORE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.restore')";

    private final AdminCommentManageService commentManage;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminCommentManageController(AdminCommentManageService commentManage,
            Messages msg) {
        this.commentManage = commentManage;
        this.msg = msg;
    }

    @PostMapping("/admin/comments/{id}/takedown")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("reason") String reason, RedirectAttributes flash) {
        try {
            commentManage.takedownComment(id, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.comment.takenDown"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/comments";
    }

    @PostMapping("/admin/comments/{id}/restore")
    @PreAuthorize(RESTORE_AUTH)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            RedirectAttributes flash) {
        commentManage.restoreComment(id, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", msg.get("admin.flash.comment.restored"));
        return "redirect:/admin/comments";
    }
}

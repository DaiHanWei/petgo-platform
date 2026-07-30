package com.tailtopia.admin.comment.web;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 后台评论管理页（Story 9.9）——两线合并后本控制器只承担【列表页】。
 *
 * <p>下架/恢复 POST（{@code /admin/comments/{id}/takedown|restore}）由内容审核线
 * {@code AdminCommentManageController} 承接（FR-55A 语义 + 必填原因 + 通知/违规计数），
 * 本线原 POST 在合并时移除（同路径撞车 + 软删语义与审核模型冲突）。
 */
@Controller
public class AdminCommentModerationController {

    private static final String TAKEDOWN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";

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
}

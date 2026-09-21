package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import com.tailtopia.admin.comment.web.AdminCommentModerationController;
import com.tailtopia.admin.moderation.service.AdminCommentManageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ui.Model;
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
 *
 * <h2>V1.3.0 Story 7.2</h2>
 * B2 抽屉里的两个按钮打到这两个<b>原端点</b>（路径与参数逐字不变），只多一条 htmx 分支：
 * 成功回重渲染的抽屉 + oob 列表行 + toast，并发 {@link AdminCommentModerationController#LIST_REFRESH}
 * 让列表按当前筛选重拉（行状态变了之后，它在当前筛选下可能整行都不该在，摘要条也全是旧值）。
 * 失败交给 {@code AdminBusinessExceptionAdvice} 出 422 / 403 行内 err。
 */
@Controller
public class AdminCommentManageController {

    private static final String TAKEDOWN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";
    private static final String RESTORE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.restore')";

    private final AdminCommentManageService commentManage;

    /** 抽屉与 oob 行的数据源（Story 7.2）：与列表页同一套投影，避免两处各拼一份。 */
    private final AdminCommentModerationService inspect;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminCommentManageController(AdminCommentManageService commentManage,
            AdminCommentModerationService inspect, Messages msg) {
        this.commentManage = commentManage;
        this.inspect = inspect;
        this.msg = msg;
    }

    @PostMapping("/admin/comments/{id}/takedown")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("reason") String reason, HxRequest hx, Model model,
            HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            commentManage.takedownComment(id, reason, admin.getAdminAccountId());
            return afterAction(id, msg.get("admin.flash.comment.takenDown"), model, response);
        }
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
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        commentManage.restoreComment(id, admin.getAdminAccountId());
        if (hx.isHtmx()) {
            return afterAction(id, msg.get("admin.flash.comment.restored"), model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.comment.restored"));
        return "redirect:/admin/comments";
    }

    /** 抽屉内处置成功的统一响应（Story 7.2 · AC5）：抽屉重渲染 + oob 列表行 + toast + 列表刷新事件。 */
    private String afterAction(long id, String toast, Model model, HttpServletResponse response) {
        model.addAttribute("c", inspect.detail(id));
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminCommentModerationController.LIST_REFRESH);
        return "admin/fragments/drawer-comment :: afterAction";
    }
}

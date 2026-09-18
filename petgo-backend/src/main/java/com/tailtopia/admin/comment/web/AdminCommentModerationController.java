package com.tailtopia.admin.comment.web;

import com.tailtopia.admin.comment.service.AdminCommentModerationService;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.content.domain.CommentModerationStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 后台评论管理页（Story 9.9）——两线合并后本控制器只承担【列表页】。
 *
 * <p>下架/恢复 POST（{@code /admin/comments/{id}/takedown|restore}）由内容审核线
 * {@code AdminCommentManageController} 承接（FR-55A 语义 + 必填原因 + 通知/违规计数），
 * 本线原 POST 在合并时移除（同路径撞车 + 软删语义与审核模型冲突）。
 *
 * <h2>V1.3.0 Story 7.2</h2>
 * 页签一「评论巡查」套模板 B：筛选（状态 / 帖子 ID / 关键词）+ 摘要条 + 分页 + 详情抽屉。
 * 筛选与分页都是<b>查询参数增强</b>，不新增写端点；新增的只有只读 {@code GET /admin/comments/{id}/drawer}。
 */
@Controller
public class AdminCommentModerationController {

    private static final String TAKEDOWN_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";

    /**
     * 列表局部刷新事件（Story 7.2）：抽屉里处置成功后，列表按当前筛选 + 当前页重拉表格与摘要条。
     *
     * <p>抽屉自己不需要事件 —— 两个处置端点的响应体<b>就是</b>重渲染后的抽屉。
     */
    public static final String LIST_REFRESH = com.tailtopia.admin.shared.web.AdminHxEvents.COMMENT_LIST_REFRESH;

    /** 状态下拉的候选（与筛选参数取值一一对应；{@code DELETED} 是软删，不是可见性态）。 */
    public static final java.util.List<String> STATUS_OPTIONS = java.util.List.of(
            CommentModerationStatus.VISIBLE.name(), CommentModerationStatus.UNDER_REVIEW.name(),
            CommentModerationStatus.TAKEN_DOWN.name(), CommentModerationStatus.REJECTED.name(),
            CommentModerationStatus.AUTHOR_DEACTIVATED.name(), "DELETED");

    private final AdminCommentModerationService service;

    public AdminCommentModerationController(AdminCommentModerationService service) {
        this.service = service;
    }

    @GetMapping("/admin/comments")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String list(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "postId", required = false) Long postId,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) Long open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "comments");
        // V1.3.0 Story 4.1：页签一「评论巡查」；页签二「帖子评论分布」由 AdminCommentDistributionController 承接
        model.addAttribute("tab", "inspect");
        populate(status, postId, q, page, model);
        model.addAttribute("open", open);
        // htmx 局部刷新只回表格（摘要条随 oob 一并换）；整页请求回完整视图。
        return hx.isHtmx() ? "admin/fragments/comments-inspect :: rows(true)" : "admin/comments";
    }

    /** 评论详情抽屉（AC4）：评论全文 + 所属帖子卡 + 作者卡 + 操作条；非 htmx 直达 → 回列表并自动开该抽屉。 */
    @GetMapping("/admin/comments/{id}/drawer")
    @PreAuthorize(TAKEDOWN_AUTH)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/comments?open=" + id;
        }
        model.addAttribute("active", "comments");
        model.addAttribute("c", service.detail(id));
        return "admin/fragments/drawer-comment :: drawer";
    }

    private void populate(String status, Long postId, String q, int page, Model model) {
        var found = service.search(status, postId, q, page);
        model.addAttribute("comments", found.rows());
        model.addAttribute("total", found.total());
        model.addAttribute("hasNext", found.hasNext());
        model.addAttribute("summary", service.summary(status, postId, q));
        model.addAttribute("status", status);
        model.addAttribute("postId", postId);
        model.addAttribute("q", q);
        model.addAttribute("page", found.page()); // 页码越界已回退，分页器要用实际页码
        model.addAttribute("statusOptions", STATUS_OPTIONS);
    }
}

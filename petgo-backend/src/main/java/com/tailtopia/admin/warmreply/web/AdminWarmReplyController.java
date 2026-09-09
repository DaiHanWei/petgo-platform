package com.tailtopia.admin.warmreply.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.dto.WarmReplyViews.Row;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import com.tailtopia.admin.warmreply.service.WarmReplyWorkbenchService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
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
 * A9 暖贴回复跟进工作台（V1.3.0 Story 4.4，AB-20A ③；模板 A · UI 稿 1-12）。{@code /admin/warm-replies}，SSR + htmx，不返 JSON。
 * <ul>
 * <li>权限：查看即 {@code comment.virtual_post}（不另设只读码）；SUPER_ADMIN 隐式全权。</li>
 * <li>两态页签 待跟进 / 已跟进（近 30 天）；左栏 {@code last_reply_at} 倒序每页 20 滚动；右栏帖子卡 → 线程 → 常驻回复区（身份锁定为被回复账号）。</li>
 * <li>写端点 {@code POST /{id}/reply}（form：body、idempotencyKey）与 {@code POST /{id}/read}：htmx → 200 处置 fragment
 * （{@code data-next-id} 自动下一条 + oob 删行 / 页签计数 + {@code HX-Trigger: admin:badge-refresh} + toast）；非 htmx → PRG。
 * 422 / 404 由 {@code AdminBusinessExceptionAdvice} 出行内 err。</li>
 * </ul>
 */
@Controller
public class AdminWarmReplyController {

    public static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('comment.virtual_post')";
    static final String ROUTE = "/admin/warm-replies";

    private final WarmReplyWorkbenchService workbench;
    private final WarmReplyQueueService queue;
    private final Messages msg;

    public AdminWarmReplyController(WarmReplyWorkbenchService workbench, WarmReplyQueueService queue, Messages msg) {
        this.workbench = workbench;
        this.queue = queue;
        this.msg = msg;
    }

    /** 整页；htmx 请求（滚动翻页）只回左栏行片段。{@code tab=pending|handled}（默认 pending）。 */
    @GetMapping(ROUTE)
    @PreAuthorize(AUTH)
    public String list(@RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "page", defaultValue = "0") int page, HxRequest hx, Model model) {
        model.addAttribute("active", "warm-replies");
        populateQueue(resolveTab(tab), page, model);
        return hx.isHtmx() ? "admin/fragments/warm-reply-queue :: rows" : "admin/warm-replies";
    }

    /** 左栏队列 fragment（切页签 / 翻页）。 */
    @GetMapping(ROUTE + "/queue")
    @PreAuthorize(AUTH)
    public String queueFragment(@RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        populateQueue(resolveTab(tab), page, model);
        return page > 0 ? "admin/fragments/warm-reply-queue :: rows" : "admin/fragments/warm-reply-queue :: list";
    }

    /** 右栏 fragment。 */
    @GetMapping(ROUTE + "/{id:\\d+}/detail")
    @PreAuthorize(AUTH)
    public String detail(@PathVariable long id, Model model) {
        model.addAttribute("d", workbench.detail(id, null));
        return "admin/fragments/warm-reply-detail :: detail";
    }

    /** 发布回复（AC4）：二级评论走 {@code CommentService.createReply}（D-4），项置 HANDLED/REPLIED。 */
    @PostMapping(ROUTE + "/{id:\\d+}/reply")
    @PreAuthorize(AUTH)
    public String reply(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (!hx.isHtmx()) {
            return redirectWith(flash, () -> queue.reply(id, body, idempotencyKey, admin.getAdminAccountId()), "admin.v130.warmReplies.submitted");
        }
        WarmReplyQueueService.ReplyResult r = queue.reply(id, body, idempotencyKey, admin.getAdminAccountId());
        model.addAttribute("d", workbench.detail(id, r.commentId()));
        model.addAttribute("message", msg.get(r.replayed() ? "admin.v130.comments.virtual.replayed" : "admin.v130.warmReplies.submitted"));
        return done(id, model, response);
    }

    /** 标记已读（不回复）（AC5）：无二次确认。 */
    @PostMapping(ROUTE + "/{id:\\d+}/read")
    @PreAuthorize(AUTH)
    public String read(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (!hx.isHtmx()) {
            return redirectWith(flash, () -> queue.markRead(id, admin.getAdminAccountId()), "admin.flash.warmReply.read");
        }
        queue.markRead(id, admin.getAdminAccountId());
        model.addAttribute("d", workbench.detail(id, null));
        model.addAttribute("message", msg.get("admin.flash.warmReply.read"));
        return done(id, model, response);
    }

    /** 处置成功 fragment：oob 删行 + 页签计数 + toast + 角标刷新；有下一条 → data-next-id 自动选中，没有 → 留在已处理的右栏（线程可看）。 */
    private String done(long id, Model model, HttpServletResponse response) {
        model.addAttribute("removedId", id);
        model.addAttribute("nextId", queue.nextPendingId());
        model.addAttribute("counts", workbench.counts());
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/warm-reply-done :: done";
    }

    private String redirectWith(RedirectAttributes flash, Runnable action, String noticeKey) {
        try {
            action.run();
            flash.addFlashAttribute("notice", msg.get(noticeKey));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + ROUTE;
    }

    private void populateQueue(FollowupStatus tab, int page, Model model) {
        int p = Math.max(page, 0);
        Page<Row> rows = workbench.queue(tab, p);
        Map<String, Long> counts = workbench.counts();
        String tabKey = tab == FollowupStatus.HANDLED ? "handled" : "pending";
        model.addAttribute("tab", tabKey);
        model.addAttribute("page", p);
        model.addAttribute("queue", rows);
        model.addAttribute("counts", counts);
        model.addAttribute("stateTabs", List.of(
                new StateTab(StateTab.href(ROUTE, "tab", "pending"), "admin.v130.warmReplies.tab.pending",
                        "warm-replies-tab-count-pending", counts.getOrDefault("pending", 0L), tab == FollowupStatus.PENDING),
                new StateTab(StateTab.href(ROUTE, "tab", "handled"), "admin.v130.warmReplies.tab.handled",
                        "warm-replies-tab-count-handled", counts.getOrDefault("handled", 0L), tab == FollowupStatus.HANDLED)));
    }

    /** {@code handled} → 已跟进；其余（含空 / 非法）→ 待跟进。 */
    public static FollowupStatus resolveTab(String tab) {
        return tab != null && "handled".equalsIgnoreCase(tab.trim()) ? FollowupStatus.HANDLED : FollowupStatus.PENDING;
    }
}

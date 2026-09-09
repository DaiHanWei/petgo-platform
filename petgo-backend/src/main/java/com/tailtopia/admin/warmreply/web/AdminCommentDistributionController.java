package com.tailtopia.admin.warmreply.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.dto.VirtualCommentDrawer;
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.admin.warmreply.service.VirtualCommentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 评论管理 · 页签二「帖子评论分布」（V1.3.0 Story 4.1，AB-20A ①）。
 * <ul>
 * <li>{@code GET /admin/comments/distribution}：{@code HX-Request} → 只回 {@code fragments/comments-distribution :: body}
 * （筛选栏 + 摘要条 + 表格 + 分页，htmx 替换 {@code #comments-tab-body}）；非 htmx → 整页 {@code admin/comments} 并 {@code tab=distribution}。</li>
 * <li>权限：SUPER_ADMIN / {@code comment.virtual_post}（可评）/ {@code content.view}（只看，「去评论」禁用并注明所缺权限）。</li>
 * <li>自定义 N 非正整数 → 422 行内 err（{@code admin.err.comments.distribution.badCount}）。</li>
 * <li>Story 4.2：{@code GET /admin/comments/distribution/{postId}/drawer}（抽屉 fragment，带 4-1 筛选参数供「下一帖」）与
 * {@code POST /admin/comments/virtual}（以虚拟身份发一级评论，走 {@code CommentService.createTopLevel} 完整审核；成功回评论区 fragment
 * + oob 刷新已有评论列表 + toast；422 / 403 / 404 交 {@code AdminBusinessExceptionAdvice}，按 {@code HX-Target}（#vc-error）行内 err）。</li>
 * </ul>
 */
@Controller
public class AdminCommentDistributionController {

    public static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('comment.virtual_post') or hasAuthority('content.view')";
    /** 「去评论」可用条件（Story 4.2 端点同码）。 */
    public static final String COMMENT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('comment.virtual_post')";

    private final PostDistributionQuery query;
    private final VirtualCommentService virtualComments;
    private final Messages msg;

    public AdminCommentDistributionController(PostDistributionQuery query, VirtualCommentService virtualComments, Messages msg) {
        this.query = query;
        this.virtualComments = virtualComments;
        this.msg = msg;
    }

    @GetMapping("/admin/comments/distribution")
    @PreAuthorize(VIEW_AUTH)
    public String distribution(@RequestParam(value = "count", required = false) String count,
            @RequestParam(value = "n", required = false) String n,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "excludeVirtual", required = false) Boolean excludeVirtual,
            @RequestParam(value = "page", required = false) Integer page,
            HxRequest hx, Model model) {
        Integer nn = parseN(n);
        DistributionFilter filter;
        if (hx.isHtmx()) {
            // htmx：非法 N → AppException → AdminBusinessExceptionAdvice 422 行内 err（页面把 HX-Target 改到 #admin-inline-error，不清空页签体）
            filter = DistributionFilter.of(count, nn, from, to, species, status, excludeVirtual, page);
            populate(filter, model);
            return "admin/fragments/comments-distribution :: body";
        }
        // 整页（手输 URL / 刷新历史）：非法 N 降级为「全部」并挂 error 提示，不能吐 ProblemDetail JSON
        try {
            filter = DistributionFilter.of(count, nn, from, to, species, status, excludeVirtual, page);
        } catch (AppException e) {
            model.addAttribute("error", msg.resolve(e));
            filter = DistributionFilter.of("all", null, from, to, species, status, excludeVirtual, page);
        }
        populate(filter, model);
        model.addAttribute("active", "comments");
        model.addAttribute("tab", "distribution");
        return "admin/comments";
    }

    // ===== Story 4.2：「去评论」抽屉 =====

    /** 抽屉 fragment（AC1 / AC2 / AC4 / AC5）：帖子预览 + 已有评论 + 常驻评论区 + 下一帖（按 4-1 当前筛选）。非 htmx 访问回整页。 */
    @GetMapping("/admin/comments/distribution/{postId}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable long postId,
            @RequestParam(value = "count", required = false) String count,
            @RequestParam(value = "n", required = false) String n,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "excludeVirtual", required = false) Boolean excludeVirtual,
            @RequestParam(value = "page", required = false) Integer page,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/comments/distribution";
        }
        DistributionFilter filter = lenientFilter(count, n, from, to, species, status, excludeVirtual, page);
        model.addAttribute("d", virtualComments.drawer(postId, filter, null));
        model.addAttribute("filter", filter);
        model.addAttribute("selectedIdentity", null);
        return "admin/fragments/drawer-comment-virtual :: drawer";
    }

    /**
     * 以虚拟身份发一级评论（AC3～AC6）。成功：`HX-Retarget: #vc-composer` + `HX-Reswap: outerHTML` 回评论区（文本框清空、身份保持、
     * 幂等键刷新）+ oob 刷新已有评论列表（刚提交那条置顶标「审核中」）+ toast「已提交，审核通过后显示」。失败让 AppException 冒出
     * （422 L1 命中 / 身份无效 / 正文无效；404 帖子已删）→ advice 按 HX-Target（#vc-error）回行内 err，表单不消失。
     */
    @PostMapping("/admin/comments/virtual")
    @PreAuthorize(COMMENT_AUTH)
    public String postVirtual(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("postId") long postId,
            @RequestParam(value = "virtualUserId", required = false) Long virtualUserId,
            @RequestParam(value = "body", required = false) String body,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey,
            @RequestParam(value = "count", required = false) String count,
            @RequestParam(value = "n", required = false) String n,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "excludeVirtual", required = false) Boolean excludeVirtual,
            @RequestParam(value = "page", required = false) Integer page,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/comments/distribution";
        }
        if (virtualUserId == null) {
            throw AppException.validation("请选择评论身份").code("admin.err.virtualComment.identityInvalid");
        }
        VirtualCommentService.Result r = virtualComments.postAsVirtual(postId, virtualUserId, body, idempotencyKey,
                admin.getAdminAccountId());
        DistributionFilter filter = lenientFilter(count, n, from, to, species, status, excludeVirtual, page);
        VirtualCommentDrawer d = virtualComments.drawer(postId, filter, r.commentId());
        model.addAttribute("d", d);
        model.addAttribute("filter", filter);
        model.addAttribute("selectedIdentity", virtualUserId);
        model.addAttribute("oob", true);
        model.addAttribute("toast", msg.get(r.replayed() ? "admin.v130.comments.virtual.replayed" : "admin.v130.comments.virtual.submitted"));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#vc-composer");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "outerHTML");
        return "admin/fragments/drawer-comment-virtual :: composer-with-existing";
    }

    /** 抽屉 / 发布路径的筛选只用于算「下一帖」，非法 N 直接当「全部」，不报错。 */
    private static DistributionFilter lenientFilter(String count, String n, LocalDate from, LocalDate to, String species,
            String status, Boolean excludeVirtual, Integer page) {
        try {
            return DistributionFilter.of(count, parseN(n), from, to, species, status, excludeVirtual, page);
        } catch (AppException e) {
            return DistributionFilter.of("all", null, from, to, species, status, excludeVirtual, page);
        }
    }

    /** N 原文：空 → null；非数字 → -1（由 {@code DistributionFilter.of} 判成非正整数 → 422，而不是 400 JSON）。 */
    public static Integer parseN(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void populate(DistributionFilter filter, Model model) {
        PostDistributionQuery.Result r = query.fetch(filter);
        model.addAttribute("filter", filter);
        model.addAttribute("rows", r.rows());
        model.addAttribute("total", r.total());
        model.addAttribute("hasNext", (long) (filter.page() + 1) * DistributionFilter.PAGE_SIZE < r.total());
        model.addAttribute("summary", r.summary());
        model.addAttribute("speciesOptions", DistributionFilter.SPECIES);
    }
}

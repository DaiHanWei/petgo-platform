package com.tailtopia.admin.warmreply.web;

import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.repository.PostDistributionQuery;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 评论管理 · 页签二「帖子评论分布」（V1.3.0 Story 4.1，AB-20A ①）。
 * <ul>
 * <li>{@code GET /admin/comments/distribution}：{@code HX-Request} → 只回 {@code fragments/comments-distribution :: body}
 * （筛选栏 + 摘要条 + 表格 + 分页，htmx 替换 {@code #comments-tab-body}）；非 htmx → 整页 {@code admin/comments} 并 {@code tab=distribution}。</li>
 * <li>权限：SUPER_ADMIN / {@code comment.virtual_post}（可评）/ {@code content.view}（只看，「去评论」禁用并注明所缺权限）。</li>
 * <li>自定义 N 非正整数 → 422 行内 err（{@code admin.err.comments.distribution.badCount}）。</li>
 * <li>「去评论」抽屉端点 {@code /admin/comments/distribution/{postId}/drawer} 由 Story 4.2 实现，本 story 只落按钮。</li>
 * </ul>
 */
@Controller
public class AdminCommentDistributionController {

    public static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('comment.virtual_post') or hasAuthority('content.view')";
    /** 「去评论」可用条件（Story 4.2 端点同码）。 */
    public static final String COMMENT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('comment.virtual_post')";

    private final PostDistributionQuery query;
    private final Messages msg;

    public AdminCommentDistributionController(PostDistributionQuery query, Messages msg) {
        this.query = query;
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

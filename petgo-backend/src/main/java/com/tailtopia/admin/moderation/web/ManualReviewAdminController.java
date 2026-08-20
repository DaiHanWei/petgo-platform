package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.domain.ReviewPriority;
import com.tailtopia.admin.moderation.service.AdminSettingsService;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.moderation.ModerationDecision;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 人工审核队列（Story 4.3，AB-3C，预建未激活）。SSR + HTMX，{@code /admin/manual-review}，不返 JSON。
 * 门控分层（Dev Notes）：**激活开关仅 {@code SUPER_ADMIN}**；队列入口 + 处置（通过/拒绝）
 * {@code SUPER_ADMIN} 或 {@code content.manual_review}（处置额外接受历史 {@code content.takedown}）。
 * 内容状态变更经 {@link ManualReviewService} → {@code ContentService}（禁直读 content repo）。
 */
@Controller
public class ManualReviewAdminController {

    private static final String QUEUE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('content.manual_review')";
    private static final String TOGGLE_AUTH = "hasRole('SUPER_ADMIN')";
    private static final String DECIDE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.takedown') or hasAuthority('content.manual_review')";

    /**
     * 本页作用域（2026-08-19 拆分）：内容举报 + 账号标识字段（名称 / 头像）。
     *
     * <p>用户举报**不在此列** —— 它的处置是账号级（警告 / 封号），归「被举报用户」页。
     *
     * <p>内容送审（{@code CONTENT_SUBMISSION}）已于 2026-08-19 第二步并入本列表，
     * 页内原先那张独立表格随之移除 —— 两张表并列会让运营以为「上面那张看完了就没事了」。
     * 拆开的理由：账号标识字段这一类此前挤在工单队列页里，那页的批量按钮全是账号处置与内容下架权，
     * **对它一个都用不上**，运营点了只会吃一条「请到名称/头像审核页处理」的红字。
     */
    private static final java.util.Set<TicketType> SCOPE = java.util.EnumSet.of(
            TicketType.CONTENT_REPORT, TicketType.ACCOUNT_IDENTITY, TicketType.CONTENT_SUBMISSION);

    private static final int PAGE_SIZE = 20;

    private final ManualReviewService reviewService;
    private final AdminSettingsService settingsService;
    private final UnifiedTicketQueryService ticketQuery;

    public ManualReviewAdminController(ManualReviewService reviewService,
            AdminSettingsService settingsService, UnifiedTicketQueryService ticketQuery) {
        this.reviewService = reviewService;
        this.settingsService = settingsService;
        this.ticketQuery = ticketQuery;
    }

    @GetMapping("/admin/manual-review")
    @PreAuthorize(QUEUE_AUTH)
    public String queue(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        model.addAttribute("active", "manual-review");
        // ⚠️ 这个开关管的是**内容发布要不要走人工审核这道关**（默认关＝机器判完直接发布），
        // 不是页面开关。下面的复核列表与它无关、始终显示。按钮本身仅超管可见（模板 sec:authorize）。
        model.addAttribute("manualReviewEnabled", settingsService.isManualReviewEnabled());

        // 复核列表（2026-08-19 从工单队列页移入）：内容举报 + 账号标识字段混排，按类型筛选。
        TicketType typeFilter = parseTicketType(type);
        TicketStatusBucket statusFilter = parseStatusBucket(status);
        model.addAttribute("result", ticketQuery.search(SCOPE, typeFilter, statusFilter, q,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        model.addAttribute("types", SCOPE.toArray(new TicketType[0]));
        model.addAttribute("statuses", TicketStatusBucket.values());
        model.addAttribute("type", typeFilter == null ? null : typeFilter.name());
        model.addAttribute("status", statusFilter == null ? null : statusFilter.name());
        model.addAttribute("q", q);

        return hxRequest != null ? "admin/manual-review :: rows" : "admin/manual-review";
    }

    /** 非法/超出作用域的类别一律当「不筛选」，绝不让改 URL 把整页打成 500。 */
    private static TicketType parseTicketType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            TicketType t = TicketType.valueOf(raw.trim());
            return SCOPE.contains(t) ? t : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static TicketStatusBucket parseStatusBucket(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TicketStatusBucket.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @PostMapping("/admin/settings/manual-review")
    @PreAuthorize(TOGGLE_AUTH)
    public String toggle(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("enabled") boolean enabled, RedirectAttributes flash) {
        settingsService.setManualReviewEnabled(enabled, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", enabled
                ? "已开启人工审核（未过自动审核的内容将入队挂起）"
                : "已关闭人工审核（恢复现网行为：拦截即发布失败）");
        return "redirect:/admin/manual-review";
    }

    @PostMapping("/admin/manual-review/{itemId}/approve")
    @PreAuthorize(DECIDE_AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long itemId,
            RedirectAttributes flash) {
        try {
            reviewService.approve(itemId, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已通过（内容已发布并通知作者）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/manual-review";
    }

    @PostMapping("/admin/manual-review/{itemId}/reject")
    @PreAuthorize(DECIDE_AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long itemId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "note", required = false) String note,
            RedirectAttributes flash) {
        try {
            // story 8 §5.2：判定依据 + 备注折叠进 append-only 审计（service 内落，无内容原文）。
            reviewService.reject(itemId, admin.getAdminAccountId(), new ModerationDecision(category, note));
            flash.addFlashAttribute("notice", "已拒绝（内容已丢弃并通知作者）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/manual-review";
    }

    /** 调整队列项优先级（story 8，§5.1）。仅 PENDING 可改；写一条 REVIEW_PRIORITY_CHANGED 审计。 */
    @PostMapping("/admin/manual-review/{itemId}/priority")
    @PreAuthorize(DECIDE_AUTH)
    public String changePriority(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long itemId,
            @RequestParam("priority") String priority, RedirectAttributes flash) {
        try {
            reviewService.changePriority(itemId, parsePriority(priority), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已调整优先级为 " + priority.trim().toUpperCase());
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/manual-review";
    }

    private static ReviewPriority parsePriority(String raw) {
        if (raw == null) {
            throw AppException.validation("优先级必填（P0 / P1 / P2）");
        }
        try {
            return ReviewPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("优先级非法，须为 P0 / P1 / P2 之一");
        }
    }
}

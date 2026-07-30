package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.domain.ReviewPriority;
import com.tailtopia.admin.moderation.service.AdminSettingsService;
import com.tailtopia.admin.moderation.service.ManualReviewService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.content.moderation.ModerationDecision;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * 门控分层（Dev Notes）：**队列入口 + 激活开关仅 {@code SUPER_ADMIN}**；处置（通过/拒绝）{@code content.takedown}。
 * 内容状态变更经 {@link ManualReviewService} → {@code ContentService}（禁直读 content repo）。
 */
@Controller
public class ManualReviewAdminController {

    private static final String ENTRY_AUTH = "hasRole('SUPER_ADMIN')";
    private static final String DECIDE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')";

    private final ManualReviewService reviewService;
    private final AdminSettingsService settingsService;

    public ManualReviewAdminController(ManualReviewService reviewService,
            AdminSettingsService settingsService) {
        this.reviewService = reviewService;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/manual-review")
    @PreAuthorize(ENTRY_AUTH)
    public String queue(@RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        model.addAttribute("active", "manual-review");
        model.addAttribute("manualReviewEnabled", settingsService.isManualReviewEnabled());
        model.addAttribute("items", reviewService.pendingQueue());
        return hxRequest != null ? "admin/manual-review :: rows" : "admin/manual-review";
    }

    @PostMapping("/admin/settings/manual-review")
    @PreAuthorize(ENTRY_AUTH)
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

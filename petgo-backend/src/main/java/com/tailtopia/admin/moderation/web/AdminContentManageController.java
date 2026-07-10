package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 后台全量内容管理（Story 4.2，AB-3B）。SSR + HTMX，{@code /admin/content}，**不返 JSON**。
 * 浏览/筛选/搜索经 {@link AdminContentManageService} → {@code ContentService}（禁直读 content repo）。
 * 门控：浏览/下架 {@code content.proactive_takedown}；恢复 {@code content.restore}；{@code SUPER_ADMIN} 隐式全权。
 */
@Controller
public class AdminContentManageController {

    private static final String BROWSE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.proactive_takedown')";
    private static final String RESTORE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.restore')";

    private final AdminContentManageService contentManage;

    public AdminContentManageController(AdminContentManageService contentManage) {
        this.contentManage = contentManage;
    }

    @GetMapping("/admin/content")
    @PreAuthorize(BROWSE_AUTH)
    public String content(@RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "content");
        model.addAttribute("type", type);
        model.addAttribute("authorId", authorId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("status", status);
        model.addAttribute("q", q);
        model.addAttribute("page", page);
        model.addAttribute("items", contentManage.browse(type, authorId, from, to, status, q, page));
        return hxRequest != null ? "admin/content :: rows" : "admin/content";
    }

    @PostMapping("/admin/content/{postId}/takedown")
    @PreAuthorize(BROWSE_AUTH)
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long postId,
            @RequestParam("reason") String reason,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, RedirectAttributes flash) {
        try {
            contentManage.takedown(postId, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已下架该内容（已通知作者，操作留审计）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        // HTMX：只回该行片段（原地替换、不整页刷新、不回顶）；非 HTMX 退回 PRG 整页。
        return rowOrRedirect(hxRequest, postId, model);
    }

    @PostMapping("/admin/content/{postId}/restore")
    @PreAuthorize(RESTORE_AUTH)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long postId,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, RedirectAttributes flash) {
        contentManage.restore(postId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已恢复该内容（重新进入公开口径；评论/点赞不随恢复）");
        return rowOrRedirect(hxRequest, postId, model);
    }

    /** HTMX 请求 → 回单行片段（原地替换当前行）；否则 PRG 整页重定向。 */
    private String rowOrRedirect(String hxRequest, long postId, Model model) {
        if (hxRequest != null) {
            model.addAttribute("c", contentManage.row(postId));
            return "admin/content :: row";
        }
        return "redirect:/admin/content";
    }
}

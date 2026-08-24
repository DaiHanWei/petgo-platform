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
    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.view') or hasAuthority('content.proactive_takedown')";
    private static final String RESTORE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.restore')";

    private final AdminContentManageService contentManage;
    private final com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead;

    public AdminContentManageController(AdminContentManageService contentManage,
            com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead) {
        this.contentManage = contentManage;
        this.throttleRead = throttleRead;
    }

    @GetMapping("/admin/content")
    @PreAuthorize(VIEW_AUTH)
    public String content(@RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            // V1.1.6 Story 14.1 · AC5：按物种与按**推导来源**筛选。
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "speciesSource", required = false) String speciesSource,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "content");
        model.addAttribute("type", type);
        model.addAttribute("authorId", authorId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("status", status);
        model.addAttribute("q", q);
        model.addAttribute("page", page);
        model.addAttribute("species", species);
        model.addAttribute("speciesSource", speciesSource);
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        model.addAttribute("speciesSources",
                com.tailtopia.content.species.SpeciesSource.values());
        // 🔴 带物种信息的行：整页一次推导（逐行会是 N+1）。
        var items = contentManage.browseWithSpecies(type, authorId, from, to,
                status, q, page, species, speciesSource);
        model.addAttribute("items", items);
        // Story 17.2 · AC3：限流状态列。🔴 整页一次取（逐行查就是 N+1，
        // 与上面物种推导同一份教训）。
        model.addAttribute("throttles", throttleRead.forPosts(
                items.stream().map(r -> r.content().id()).toList(),
                items.stream().collect(java.util.stream.Collectors.toMap(
                        r -> r.content().id(), r -> r.content().authorId(), (a, b) -> a)),
                java.time.Instant.now()));
        return hxRequest != null ? "admin/content :: rows" : "admin/content";
    }

    /**
     * 设置 / 清除行级物种覆写（V1.1.6 Story 14.1 · AC5），**支持批量**。
     *
     * <p>🔴 这是存量种子内容**唯一**的物种修正入口 —— 触点 ②③ 都只在发布时存在。
     * 🛡 真实用户内容只读，由服务层权威校验（界面不给按钮只是体验）。
     */
    @PostMapping("/admin/content/species")
    // 🛡 与"主动下架"同一道门（BROWSE_AUTH）：改物种归属是**内容运营动作**，
    //    不该比下架更松。⚠️ 刻意不新增权限码 —— 新码要冻结、要分配、要进勾选清单，
    //    而这里没有一个只该改物种却不该下架的角色。
    @PreAuthorize(BROWSE_AUTH)
    public String setSpecies(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("postId") java.util.List<Long> postIds,
            @RequestParam(value = "species", required = false) String species,
            RedirectAttributes flash) {
        try {
            int changed = contentManage.setSpeciesOverride(postIds, species,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已更新 " + changed + " 条内容的物种归属"
                    + (changed < postIds.size()
                            ? "（其余为真实用户内容，物种由其宠物档案决定，不可手工干预）" : ""));
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/content";
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
            // ⚠️ 这条 HTMX 路径只刷一行、**不放 `sp`**（物种推导要查作者+档案，
            //    整页那次已经批量算过；为一行再算一次不值当）。
            //    片段里 `sp` 未定义 ⇒ 物种两列渲染成 '—'。
            //    🔴 这是刻意的取舍：下架/恢复之后那一行的物种并没有变化，
            //    整页刷新时会显示正确值。
            return "admin/content :: row";
        }
        return "redirect:/admin/content";
    }
}

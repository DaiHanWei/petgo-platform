package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.service.AdminContentManageService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
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
    /** ⚠️ 须与模板里导出按钮的 sec:authorize 逐字一致，否则按钮在、点了 403。 */
    private static final String EXPORT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.list_export')";

    private final AdminContentManageService contentManage;
    private final com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminContentManageController(AdminContentManageService contentManage,
            Messages msg,
            com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead) {
        this.contentManage = contentManage;
        this.msg = msg;
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
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            // V1.1.6 Story 14.1 · AC5：按物种与按**推导来源**筛选。
            @RequestParam(value = "species", required = false) String species,
            @RequestParam(value = "speciesSource", required = false) String speciesSource,
            // 2026-08-28：日期范围的口径。"published"（默认）= 按发布时间筛内容，
            // 点赞列是至今累计；"liked" = 按**点赞发生的时间**，列出这段时间里被点过赞的内容，
            // 点赞列是**窗口内**的赞数。两者回答的是两个不同的问题，见服务层注释。
            @RequestParam(value = "dateBasis", required = false, defaultValue = "published")
            String dateBasis,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "content");
        model.addAttribute("dateBasis", dateBasis);
        model.addAttribute("type", type);
        model.addAttribute("authorId", authorId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("status", status);
        model.addAttribute("q", q);
        model.addAttribute("sort", sort);
        model.addAttribute("page", page);
        model.addAttribute("species", species);
        model.addAttribute("speciesSource", speciesSource);
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        model.addAttribute("speciesSources",
                com.tailtopia.content.species.SpeciesSource.values());
        // 🔴 带物种信息的行：整页一次推导（逐行会是 N+1）。
        //
        // ⚠️ 「按点赞时间」口径**不走物种推导那条路**：它的行序由窗口内赞数决定，
        //    而 browseWithSpecies 是按发布时间倒序取的一页。硬套会给出一页错的内容。
        //    代价是这一档下物种两列显示 '—'（模板里 sp 未定义时的既有行为）——
        //    这一档是做互动周报用的，物种归属不是它要回答的问题。
        java.util.List<com.tailtopia.admin.moderation.dto.ContentSpeciesRow> items;
        java.util.Map<Long, Long> likeCounts;
        if ("liked".equals(dateBasis)) {
            var win = contentManage.browseByLikeWindow(type, authorId, from, to, status, q, page);
            // ⚠️ species 传 NONE 而不是 null：模板里 speciesLabel() 会解引用它。
            //    editable=false —— 这一档不提供物种编辑（行序不是按发布时间，改起来容易错行）。
            items = win.rows().stream()
                    .map(r -> new com.tailtopia.admin.moderation.dto.ContentSpeciesRow(
                            r, com.tailtopia.content.species.ResolvedSpecies.NONE, false))
                    .toList();
            likeCounts = win.windowLikes();
            model.addAttribute("likeWindowPoolFull", win.poolFull());
            // ⚠️ 这一档的行序**固定**由窗口内赞数决定，表头排序在此无效 ——
            //    与其让运营点了没反应，不如把排序状态清掉、并在界面上说明。
            model.addAttribute("sort", null);
            model.addAttribute("sortDisabled", true);
        } else {
            items = contentManage.browseWithSpecies(type, authorId, from, to,
                    status, q, sort, page, species, speciesSource);
            likeCounts = contentManage.likeCounts(
                    items.stream().map(r -> r.content().id()).toList());
        }
        model.addAttribute("items", items);
        // Story 17.2 · AC3：限流状态列。🔴 整页一次取（逐行查就是 N+1，
        // 与上面物种推导同一份教训）。
        model.addAttribute("throttles", throttleRead.forPosts(
                items.stream().map(r -> r.content().id()).toList(),
                items.stream().collect(java.util.stream.Collectors.toMap(
                        r -> r.content().id(), r -> r.content().authorId(), (a, b) -> a)),
                java.time.Instant.now()));
        // bug 20260828：点赞数列（取代被撤掉的「内容互动积分」整页）。
        // 🔴 整页一次批量取 —— 与物种推导、限流状态同一条纪律。
        model.addAttribute("likeCounts", likeCounts);
        // 2026-08-31：浏览次数/人数列（至今累计，两个口径下都一样 —— 浏览没有逐次时间线，
        // 给不出「窗口内的浏览」，所以「按点赞时间」档也照给累计值，模板里注明）。
        model.addAttribute("viewStats", contentManage.viewStats(
                items.stream().map(r -> r.content().id()).toList()));
        return hxRequest != null ? "admin/content :: rows" : "admin/content";
    }

    /**
     * 按当前筛选条件导出 CSV（2026-08-28，取代被撤掉的「内容互动积分」页的导出）。
     *
     * <p>🔴 独立权限 {@code content.list_export} —— 与列表查看分开：
     * 查看是一次看一屏，导出是把数据**批量带出系统**。导出动作记审计。
     *
     * <p>⚠️ 参数与列表页**逐个对齐**：按钮直接把当前筛选条件带过来，
     * 导出的就是屏幕上正在看的那一份。少一个参数，运营就会导出一份跟屏幕对不上的表。
     */
    @GetMapping(value = "/admin/content/export.csv", produces = "text/csv; charset=UTF-8")
    @PreAuthorize(EXPORT_AUTH)
    public org.springframework.http.ResponseEntity<String> exportCsv(
            @AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "authorId", required = false) Long authorId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "dateBasis", required = false, defaultValue = "published")
            String dateBasis) {
        String csv = contentManage.exportCsv(admin.getAdminAccountId(), type, authorId,
                from, to, status, q, dateBasis);
        // 🔴 BOM 不能省：Excel 打开无 BOM 的 UTF-8 CSV 会把中文正文显示成乱码，
        //    运营会以为是数据坏了。（与召回名单导出同一处教训。）
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"content-list.csv\"")
                .body('﻿' + csv);
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
            flash.addFlashAttribute("notice", msg.get("admin.flash.content.takenDown"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
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
        flash.addFlashAttribute("notice", msg.get("admin.flash.content.restored"));
        return rowOrRedirect(hxRequest, postId, model);
    }

    /** HTMX 请求 → 回单行片段（原地替换当前行）；否则 PRG 整页重定向。 */
    private String rowOrRedirect(String hxRequest, long postId, Model model) {
        if (hxRequest != null) {
            model.addAttribute("c", contentManage.row(postId));
            // ⚠️ 点赞数**这一行照样给**（与下面的物种两列不同）：下架/恢复不改变赞数，
            //    但若这里不给，那一格会从「3」跳成「—」，看起来像数据丢了。
            //    一行一次 count 很便宜，不值得为省它制造一个假象。
            model.addAttribute("likeCounts", contentManage.likeCounts(java.util.List.of(postId)));
            // 浏览两列同理：下架/恢复不改变浏览数，但不给这一行就会从数字跳成"—"。
            model.addAttribute("viewStats", contentManage.viewStats(java.util.List.of(postId)));
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

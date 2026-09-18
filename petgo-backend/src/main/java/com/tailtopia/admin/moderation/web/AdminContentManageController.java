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
    /**
     * 内容详情页（2026-09-02，只读）。在 VIEW_AUTH 之上多放行两个**复核**权限 ——
     * 人工复核页有「查看内容」入口，复核员可能只有复核权而没有 content.view，
     * 点开是只读详情，不该 403。⚠️ 须与 manual-review.html 里该链接的 sec:authorize 逐字一致。
     */
    private static final String DETAIL_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.view')"
                    + " or hasAuthority('content.proactive_takedown')"
                    + " or hasAuthority('content.takedown')"
                    + " or hasAuthority('content.manual_review')";

    /**
     * 内容页局部刷新事件（V1.3.0 Story 7.1）——<b>列表</b>与<b>抽屉</b>分两个，不能合成一个。
     *
     * <p>本页三个内容端点处置成功时回的就是抽屉本体（{@code afterAction}），只需要让**列表**重拉
     * （行 oob 只换那一行：状态变了但筛选口径下它可能整行都不该在了，摘要条五格也全是旧值）。
     * 若把抽屉刷新混在同一个事件里，抽屉会在刚渲染完后又被重拉一遍 —— 多一次请求、闪一下、
     * 还丢掉响应里刚带回来的状态。
     *
     * <p>限流两个端点（{@code /admin/throttles}、{@code /admin/throttles/lift}）与 A2 工单页共用控制器，
     * story 要求**参数零变更** ⇒ 用它们既有的 {@code back=content} 分辨调用方，两个事件都发：
     * 它们的响应体只是 toast，抽屉与列表都得自己去重拉。
     */
    public static final String LIST_REFRESH = com.tailtopia.admin.shared.web.AdminHxEvents.CONTENT_LIST_REFRESH;

    /** 见 {@link #LIST_REFRESH}：只有限流两个端点会发（本页自己的处置响应里已经带回了抽屉）。 */
    public static final String DRAWER_REFRESH = com.tailtopia.admin.shared.web.AdminHxEvents.CONTENT_DRAWER_REFRESH;

    private final AdminContentManageService contentManage;
    private final com.tailtopia.admin.moderation.service.AdminContentDetailService contentDetail;
    private final com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminContentManageController(AdminContentManageService contentManage,
            Messages msg,
            com.tailtopia.admin.throttle.service.AdminThrottleReadService throttleRead,
            com.tailtopia.admin.moderation.service.AdminContentDetailService contentDetail) {
        this.contentManage = contentManage;
        this.msg = msg;
        this.throttleRead = throttleRead;
        this.contentDetail = contentDetail;
    }

    /**
     * 内容详情抽屉（V1.3.0 Story 7.1 · AC4）：吸收原 {@code /admin/content/{postId}} 整页详情
     * （D-23：整页路由**已删且不做跳转**；外部入口一律改成 {@code /admin/content?open=<id>} 深链）。
     *
     * <p>正文全文 / 大图 / 数据卡 / 物种归属 / 限流 / 评论区，字段与原详情页一个不少；
     * 处置动作在抽屉操作条上，端点与参数仍是 4.2 / 14.1 / 17.2 那几个（本 story 只加 htmx 返回分支）。
     * 已下架 / 审核挂起 / 私密内容照常可开（复核场景恰恰要看这些），区块里标注状态。
     * 非 htmx 直达 → 回列表并自动开该抽屉。
     */
    @GetMapping("/admin/content/{postId}/drawer")
    @PreAuthorize(DETAIL_AUTH)
    public String drawer(@PathVariable long postId,
            @RequestParam(value = "commentPage", required = false, defaultValue = "0")
            int commentPage,
            @RequestParam(value = "expand", required = false) Long expand,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/content?open=" + postId;
        }
        populateDrawer(postId, commentPage, expand, model);
        return "admin/fragments/drawer-content :: drawer";
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
            // ?open=<postId> 深链（D-23：A1 复核队列 / A2 工单 / 暖贴跟进的「查看内容」都落这里）。
            // ⚠️ 目标帖十有八九不在首页 20 行里，光靠 admin-drawer.js 按行找会静默什么都不发生 ——
            //    所以把 id 原样带进模板，渲染一个按 id 直取抽屉的兜底入口。
            @RequestParam(value = "open", required = false) Long open,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        model.addAttribute("active", "content");
        model.addAttribute("open", open);
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
        // 「数据库那一页取满了没有」——物种过滤发生在应用层，判有无下一页只能用过滤前的行数。
        boolean dbPageFull = false;
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
            var pageResult = contentManage.browseWithSpeciesPage(type, authorId, from, to,
                    status, q, sort, page, species, speciesSource);
            items = pageResult.rows();
            dbPageFull = pageResult.dbPageFull();
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
        // AC6：作者注销 → 列表与抽屉都置灰 + #id。整页一次批量取（同上三条同一纪律）。
        model.addAttribute("authors", contentManage.authorViews(
                items.stream().map(r -> r.content().authorId()).toList()));
        // AC2：摘要条五格随当前筛选联动（单条聚合查询）。
        var summary = contentManage.summary(type, authorId, from, to, status, q, dateBasis);
        model.addAttribute("summary", summary);
        // 分页：默认口径用摘要条的总数判；带物种筛选时用「数据库那一页取满了没有」（过滤前的行数，
        // 否则筛出 3 行就再也翻不到第 21 条）；「按点赞时间」档的行来自内存池，按本页是否装满判。
        boolean speciesFiltered = (species != null && !species.isBlank())
                || (speciesSource != null && !speciesSource.isBlank());
        model.addAttribute("speciesFiltered", speciesFiltered);
        boolean hasNext;
        if ("liked".equals(dateBasis)) {
            hasNext = items.size() >= AdminContentManageService.PAGE_SIZE;
        } else if (speciesFiltered) {
            hasNext = dbPageFull;
        } else {
            hasNext = (long) (Math.max(page, 0) + 1) * AdminContentManageService.PAGE_SIZE
                    < summary.total();
        }
        model.addAttribute("hasNext", hasNext);
        return hxRequest != null ? "admin/fragments/content-list :: rows(true)" : "admin/content";
    }

    /** 抽屉模型：详情聚合 + 物种归属 + 限流态；顺带备齐 oob 行片段要用的四张表。 */
    private void populateDrawer(long postId, int commentPage, Long expand, Model model) {
        model.addAttribute("active", "content");
        model.addAttribute("d", contentDetail.detail(postId, commentPage, expand));
        model.addAttribute("expand", expand);
        var sp = contentManage.speciesRow(postId);
        model.addAttribute("sp", sp);
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
        Long authorId = sp.content().authorId();
        var throttles = throttleRead.forPosts(java.util.List.of(postId),
                authorId == null ? java.util.Map.of() : java.util.Map.of(postId, authorId),
                java.time.Instant.now());
        model.addAttribute("throttle", throttles.get(postId));
        // 以下四项供 afterAction 里的 oob 列表行复用（与整页列表同一片段、同一口径）。
        model.addAttribute("throttles", throttles);
        model.addAttribute("likeCounts", contentManage.likeCounts(java.util.List.of(postId)));
        model.addAttribute("viewStats", contentManage.viewStats(java.util.List.of(postId)));
        model.addAttribute("authors", contentManage.authorViews(
                authorId == null ? java.util.List.of() : java.util.List.of(authorId)));
    }

    /**
     * 抽屉内处置成功的统一响应（AC5）：抽屉重渲染 + oob 列表行 + toast；
     * 失败交给 {@code AdminBusinessExceptionAdvice} 出 422 / 403 行内 err（不在这里 try/catch）。
     */
    private String afterAction(long postId, String toast, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        populateDrawer(postId, 0, null, model);
        model.addAttribute("toast", toast);
        // 行 oob 只换那一行 —— 但状态变了之后它在当前筛选下可能整行都不该在，摘要条五格也全是旧值。
        com.tailtopia.admin.shared.web.AdminFragmentResponses.trigger(response, LIST_REFRESH);
        return "admin/fragments/drawer-content :: afterAction";
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
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        // Story 7.1：抽屉里的物种卡是单条提交 —— 成功回抽屉 + oob 行；批量入口（非 htmx）维持 PRG。
        if (hx.isHtmx() && !postIds.isEmpty()) {
            int changed = contentManage.setSpeciesOverride(postIds, species, admin.getAdminAccountId());
            return afterAction(postIds.get(0), msg.get(changed < postIds.size()
                    ? "admin.flash.content.speciesUpdatedPartial"
                    : "admin.flash.content.speciesUpdated", changed), model, response);
        }
        try {
            int changed = contentManage.setSpeciesOverride(postIds, species,
                    admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get(changed < postIds.size()
                    ? "admin.flash.content.speciesUpdatedPartial"
                    : "admin.flash.content.speciesUpdated", changed));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content";
    }

    @PostMapping("/admin/content/{postId}/takedown")
    @PreAuthorize(BROWSE_AUTH)
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long postId,
            @RequestParam("reason") String reason,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        // htmx（抽屉操作条）：成功回抽屉 + oob 行 + toast；原因为空之类的业务错由 advice 出 422 行内 err。
        if (hx.isHtmx()) {
            contentManage.takedown(postId, reason, admin.getAdminAccountId());
            return afterAction(postId, msg.get("admin.flash.content.takenDown"), model, response);
        }
        try {
            contentManage.takedown(postId, reason, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.content.takenDown"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/content";
    }

    @PostMapping("/admin/content/{postId}/restore")
    @PreAuthorize(RESTORE_AUTH)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long postId,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model,
            jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            contentManage.restore(postId, admin.getAdminAccountId());
            return afterAction(postId, msg.get("admin.flash.content.restored"), model, response);
        }
        contentManage.restore(postId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", msg.get("admin.flash.content.restored"));
        return "redirect:/admin/content";
    }
}

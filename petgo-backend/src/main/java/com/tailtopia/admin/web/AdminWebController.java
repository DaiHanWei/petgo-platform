package com.tailtopia.admin.web;

import com.tailtopia.admin.dto.CreateVetForm;
import com.tailtopia.admin.dto.EditVetForm;
import com.tailtopia.admin.dto.SeedPostForm;
import com.tailtopia.admin.dto.VetListFilter;
import com.tailtopia.admin.vetqual.domain.QualificationStatus;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.admin.service.AdminContentService;
import com.tailtopia.admin.service.AdminModerationService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.admin.virtual.service.AdminPublishIdentityService;
import com.tailtopia.admin.virtual.service.AdminVirtualAccountService;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 运营后台页面控制器（Story 3.1）。Thymeleaf 服务端渲染，走 {@code /admin/**}（与 {@code /api/v1} JSON 隔离）。
 *
 * <p>门控由 {@code SecurityConfig} 的 admin filter chain 负责（{@code role=ADMIN}，表单登录会话）。
 * 本 shell 是横切设施：导航壳已预留「举报队列(3.7) / 兽医账号·评分查看(Epic 5)」入口位（占位 disabled）。
 */
@Controller
public class AdminWebController {

    private final AdminContentService adminContentService;
    private final AdminModerationService adminModerationService;
    private final AdminVetService adminVetService;
    private final AdminVirtualAccountService virtualAccountService;
    private final AdminPublishIdentityService publishIdentityService;
    /** V1.3.0 Story 2.4：内容举报页签处置 fragment 装配（工作台）。 */
    private final com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService reviewWorkbench;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminWebController(AdminContentService adminContentService,
            AdminModerationService adminModerationService,
            AdminVetService adminVetService,
            AdminVirtualAccountService virtualAccountService,
            Messages msg,
            AdminPublishIdentityService publishIdentityService,
            com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService reviewWorkbench) {
        this.reviewWorkbench = reviewWorkbench;
        this.adminContentService = adminContentService;
        this.adminModerationService = adminModerationService;
        this.adminVetService = adminVetService;
        this.virtualAccountService = virtualAccountService;
        this.msg = msg;
        this.publishIdentityService = publishIdentityService;
    }

    /** 登录页（未认证可访问；认证失败回显 error，登出回显 logout）。 */
    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }

    /**
     * 权限不足统一落点（403）：admin 链 accessDeniedHandler forward 至此（URL 级门控与
     * {@code @PreAuthorize} 方法级拒绝均收口于此）。forward 保留原请求方法（POST 提交被拒也会到达），
     * 故不限 method。仅提示 + 返回入口，不泄露权限点细节。
     */
    @RequestMapping("/admin/denied")
    public String denied() {
        return "admin/denied";
    }

    // 运营概览 / 数据看板（GET /admin、/admin/dashboard）自 V1.3.0 Story 3.4 起由 admin/dashboard/web/AdminDashboardController 承接。

    @GetMapping("/admin/seed-post")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')")
    public String seedPostForm(Model model) {
        model.addAttribute("active", "seed");
        if (!model.containsAttribute("seedPostForm")) {
            model.addAttribute("seedPostForm", new SeedPostForm());
        }
        seedPostModel(model);
        return "admin/seed-post";
    }

    private void seedPostModel(Model model) {
        model.addAttribute("types", ContentType.values());
        model.addAttribute("accounts", virtualAccountService.list());
        // 发布账号选择器的数据源（V1.1.6 Story 12.1 · AC6）：虚拟账号 + 池内运营真实账号。
        // 🛡 三处发布入口共用同一份数据与同一个片段 —— 别在某个页面另攒一份列表。
        model.addAttribute("publishIdentities", publishIdentityService.selectableIdentities());
        // ⚠️ 宠物下拉片段在**整页渲染**时也会被 include，所以这里必须给它一个空表 ——
        //    不给的话 `petOptions.isEmpty()` 在 null 上求值，整页 500。
        //    真正的内容由 HTMX 在选定账号后从 /admin/seed-post/pets 换进来。
        model.addAttribute("petOptions", java.util.List.of());
        // V1.1.6 Story 14.1 · AC4：「关联物种」下拉（挂在发布账号选择器之后）。
        model.addAttribute("speciesOptions", com.tailtopia.content.species.ContentSpecies.ALL);
    }

    // ===== Story 3.7 + 4.1：举报审核队列（状态筛选 + 批量 + 双向通知 + 审计）=====

    // V1.3.0 Story 2.4：GET /admin/reports（旧举报队列页的 redirect 壳）与 reports.html 已退役；
    // 下面四个 POST /admin/reports/** 写端点保留（统一复核工作台的内容举报页签调用；batch 本页不用但不删）。

    @PostMapping("/admin/reports/{id}/takedown")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')")
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (hx.isHtmx()) {
            // Story 2.4 AC5：工作台 htmx 分支 → 处置 fragment（不 try/catch，422/403 由 advice 出 fragment）
            long postId = reviewWorkbench.postIdOfReport(id).orElse(id);
            adminModerationService.takedown(id, admin);
            return reportDone(hx, postId, msg.get("admin.flash.review.takenDown"), model, response);
        }
        adminModerationService.takedown(id, admin);
        // 回内容举报现在的所在页（拆分后是「人工复核」）。回 /admin/tickets 等于把人甩到另一个
        // 页面，且刚处置的那条根本不在那儿 —— 运营会以为操作没生效。
        return "redirect:/admin/manual-review";
    }

    @PostMapping("/admin/reports/{id}/dismiss")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')")
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response) {
        if (hx.isHtmx()) {
            long postId = reviewWorkbench.postIdOfReport(id).orElse(id);
            adminModerationService.dismiss(id, admin);
            return reportDone(hx, postId, msg.get("admin.flash.review.dismissedOne"), model, response);
        }
        // ⚠️ gate 对齐 dismiss-all / 批量驳回的 content.takedown（评审三轮 #2）：驳回是处置动作，
        // 挂查看权上等于让只读审核员逐条 POST 绕过处置权限（等价被禁的 dismiss-all）。
        adminModerationService.dismiss(id, admin);
        return "redirect:/admin/manual-review";
    }

    /**
     * 按帖驳回（V1.1.4 修复清单 #3）：统一队列的内容举报工单按帖聚合，驳回=该帖全部 PENDING 单收档。
     *
     * <p>⚠️ 权限对齐<b>批量</b>驳回的 {@code content.takedown}（{@code /admin/reports/batch} 同 gate），
     * <b>不是</b>单条驳回的 {@code content.view_reports}——一次抹掉整帖全部举报是批量级动作，
     * 挂在查看权上等于让只读审核员（V105 回填人群）绕过处置权限批量驳回真实举报。
     */
    @PostMapping("/admin/reports/post/{postId}/dismiss-all")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')")
    public String dismissAllForPost(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long postId,
            com.tailtopia.admin.shared.web.HxRequest hx, Model model, jakarta.servlet.http.HttpServletResponse response,
            org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        if (hx.isHtmx()) {
            int n0 = adminModerationService.dismissAllForPost(postId, admin);
            return reportDone(hx, postId, msg.get("admin.flash.review.dismissedAll", n0), model, response);
        }
        int n = adminModerationService.dismissAllForPost(postId, admin);
        flash.addFlashAttribute("notice", msg.get("admin.flash.review.dismissedAll", n));
        // 内容举报现在的所在页是「人工复核」（2026-08-19 拆分）。
        return "redirect:/admin/manual-review";
    }

    /** Story 2.4：内容举报页签处置成功 fragment（行按帖聚合 → removed = postId）。 */
    private String reportDone(com.tailtopia.admin.shared.web.HxRequest hx, long postId, String message, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        model.addAttribute("done", reviewWorkbench.afterDispose(hx.currentUrl(),
                com.tailtopia.admin.moderation.dto.ReviewTab.REPORT, postId));
        model.addAttribute("message", message);
        com.tailtopia.admin.shared.web.AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/review-done :: done";
    }

    @PostMapping("/admin/reports/batch")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')")
    public String batchReports(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("action") String action,
            @RequestParam(value = "reportIds", required = false) java.util.List<Long> reportIds,
            org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        boolean takedown = "takedown".equals(action);
        AdminModerationService.BatchResult result = adminModerationService.batch(reportIds, takedown, admin);
        flash.addFlashAttribute("notice",
                msg.get("admin.flash.seed.batchDone", result.ok(), result.failedCount()));
        return "redirect:/admin/manual-review";
    }

    // ===== Story 5.1：兽医账号 CRUD（复用本 shell）=====

    /** 兽医列表每页条数（V1.3.0 Story 9.1a · AC1）。 */
    private static final int VET_PAGE_SIZE = 20;

    @GetMapping("/admin/vets")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.view')")
    public String vets(@RequestParam(value = "accountStatus", required = false) String accountStatus,
            @RequestParam(value = "qualStatus", required = false) String qualStatus,
            @RequestParam(value = "online", required = false) String online,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "from", required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate from,
            @RequestParam(value = "to", required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
            java.time.LocalDate to,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("sort", sort);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        // ⚠️ 取一次列表喂给摘要条与表格两处：各查各的话跨秒时两个数能对不上。
        // 🔴 评价时间段的日界沿用退役的总览页那一份口径（**UTC**，from 当日 00:00、to 次日 00:00）——
        //    这一页别处的时间是 WIB，但这两个参数是从总览页原样搬过来的，改口径会让
        //    运营存的旧链接算出不同的数。差异写在筛选栏的提示里。
        var vetRows = adminVetService.list(new VetListFilter(accountStatus, qualStatus, online, q),
                sort,
                from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        // 🔴 摘要条统计的是**整个筛选集**，不是当前这一页：一个只统计本页的「在线数」
        //    会随翻页变化，而运营会把它当成总数抄进周报。
        model.addAttribute("summary", adminVetService.summary(vetRows));
        int total = vetRows.size();
        int totalPages = Math.max(1, (total + VET_PAGE_SIZE - 1) / VET_PAGE_SIZE);
        int safePage = Math.min(Math.max(page, 0), totalPages - 1);
        int offset = safePage * VET_PAGE_SIZE;
        model.addAttribute("vets", vetRows.subList(offset, Math.min(offset + VET_PAGE_SIZE, total)));
        model.addAttribute("page", safePage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalElements", (long) total);
        model.addAttribute("hasPrev", safePage > 0);
        model.addAttribute("hasNext", safePage < totalPages - 1);
        model.addAttribute("open", open);
        // 回显筛选 + 下拉候选。
        model.addAttribute("accountStatus", accountStatus);
        model.addAttribute("qualStatus", qualStatus);
        model.addAttribute("online", online);
        model.addAttribute("q", q);
        model.addAttribute("vetStatuses", VetStatus.values());
        model.addAttribute("qualStatuses", QualificationStatus.values());
        model.addAttribute("expiryStats", adminVetService.qualificationExpiryStats());
        if (!model.containsAttribute("createVetForm")) {
            model.addAttribute("createVetForm", new CreateVetForm());
        }
        // HTMX 局部刷新返表格 + 摘要条 oob；整页请求返完整视图。
        return hxRequest != null ? "admin/fragments/vets-list :: rows(true)" : "admin/vets";
    }

    @PostMapping("/admin/vets")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.create')")
    public String createVet(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("createVetForm") CreateVetForm form,
            BindingResult binding, Model model) {
        if (binding.hasErrors()) {
            // 创建表单为弹窗（vets.html）：校验失败置标志 → 整页重渲染时弹窗自动重开并回显错误。
            model.addAttribute("createVetModalOpen", true);
            populateVetList(model);
            return "admin/vets";
        }
        try {
            long id = adminVetService.create(form.getDisplayName(), form.getUsername(),
                    form.getPassword(), form.getContactPhone(), admin.getAdminAccountId());
            // 🔴 初始密码**仅本次可见**（V1.3.0 Story 9.1a · AC4 / UI 稿 6-4）：
            //    只放这一次渲染的 model，绝不进 flash / session / 审计 / 日志 ——
            //    后端不存明文，这一屏是唯一的获取窗口。必须在下面重置表单之前取。
            String issued = form.getPassword();
            model.addAttribute("createVetForm", new CreateVetForm());
            model.addAttribute("createdVetId", id);
            model.addAttribute("createdVetPassword", issued);
            populateVetList(model); // 列表已变更，重查（含新账号 + 资质/在线/均分列）
            return "admin/vets";
        } catch (AppException e) {
            binding.reject("create.failed", e.getMessage());
            model.addAttribute("createVetModalOpen", true); // 业务失败同样重开弹窗回显
            populateVetList(model);
            return "admin/vets";
        }
    }

    /** 兽医整页（非 HTMX）渲染所需 model：完整列表 + 下拉候选。 */
    private void populateVetList(Model model) {
        model.addAttribute("active", "vets");
        var rows = adminVetService.list(VetListFilter.none());
        model.addAttribute("summary", adminVetService.summary(rows));
        int totalPages = Math.max(1, (rows.size() + VET_PAGE_SIZE - 1) / VET_PAGE_SIZE);
        model.addAttribute("vets", rows.subList(0, Math.min(VET_PAGE_SIZE, rows.size())));
        model.addAttribute("page", 0);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalElements", (long) rows.size());
        model.addAttribute("hasPrev", false);
        model.addAttribute("hasNext", totalPages > 1);
        model.addAttribute("vetStatuses", VetStatus.values());
        model.addAttribute("qualStatuses", QualificationStatus.values());
        model.addAttribute("expiryStats", adminVetService.qualificationExpiryStats());
    }

    // ===== Story 2.4：编辑兽医资料（不中断会话）→ V1.3.0 Story 9.1a 并入抽屉资料页签 =====

    /**
     * 兽医抽屉（Story 9.1a · AC2）：资料 / 资质 / 评分 / 账号四页签。
     *
     * <p>📌 <b>整页 {@code GET /admin/vets/{id}/edit} 已删除</b>（AC5 / D-23）：改资料不该跳走再回来。
     * 不做旧地址跳转 —— 旧书签拿到 404 比拿到一个「看着像成功了」的重定向诚实。
     * 非 htmx 直达 → 回列表并自动开该抽屉（与 B1～B14 同一机制）。
     */
    @GetMapping("/admin/vets/{id}/drawer")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.view')")
    public String vetDrawer(@PathVariable long id,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        if (hxRequest == null) {
            return "redirect:/admin/vets?open=" + id;
        }
        populateVetDrawer(id, model);
        return "admin/fragments/drawer-vets :: drawer";
    }

    /** 抽屉四页签共用的 model。 */
    private void populateVetDrawer(long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vetId", id);
        // ⚠️ view(id) 只查一次：头像 URL 就在这份视图里，再查一遍是白白多一次 DB 往返。
        var v = adminVetService.view(id);
        model.addAttribute("vet", v);
        if (!model.containsAttribute("editVetForm")) {
            model.addAttribute("editVetForm", adminVetService.editForm(id));
        }
        model.addAttribute("currentAvatarUrl", v.avatarUrl());
        // 在线态与最后在线：原 vet-online 整页的数据，AC5 要求并进资料页签。
        model.addAttribute("presence", adminVetService.presenceOf(id));
    }

    /**
     * 抽屉内处置成功统一响应：抽屉重渲染 + toast + 列表**按当前筛选**整表重拉。
     *
     * <p>🔴 列表不在这里重算：这几个 POST 身上没有筛选参数，用 {@code VetListFilter.none()}
     * 算出来的是**全库**的四个数，而屏幕上的表格是筛选后的 —— 运营会读成统计坏了
     * （筛「ACTIVE」时摘要条会跳成全库的总数与已封禁数）。改为发
     * {@code admin:vet-list-refresh}，由页面上的刷新槽带着**当前筛选表单**去重拉，
     * 顺便省掉「为了拿一行数据把全量兽医重新装配一遍」的放大版 N+1。
     *
     * @param activeTab 重渲染后要停在哪个页签。🔴 不能一律回默认的「资料」：
     *                  重置密码的明文渲染在**账号**页签里，回默认页签等于把唯一的获取窗口盖住 ——
     *                  运营只看到一条 toast，明文在 DOM 里却是 hidden 的。
     */
    private String vetAfterAction(long id, String toast, String activeTab, Model model,
            jakarta.servlet.http.HttpServletResponse response) {
        populateVetDrawer(id, model);
        model.addAttribute("activeTab", activeTab);
        model.addAttribute("toast", toast);
        com.tailtopia.admin.shared.web.AdminFragmentResponses.trigger(response,
                com.tailtopia.admin.shared.web.AdminHxEvents.VET_LIST_REFRESH);
        return "admin/fragments/drawer-vets :: afterAction";
    }

    /** 上传/更换兽医头像（服务端落公开桶① → 回填 CDN URL）。仅图片、≤5MB。 */
    @PostMapping("/admin/vets/{id}/avatar")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.edit') or hasAuthority('vet.create')")
    public String uploadVetAvatar(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("avatar") org.springframework.web.multipart.MultipartFile avatar,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        boolean hx = hxRequest != null;
        String ct = avatar.getContentType();
        // ⚠️ 三条失败路径在抽屉里都得**落在行内错误槽**（抛 422 交给 AdminBusinessExceptionAdvice），
        //    不能像整页 PRG 那样吞成一句 flash —— 抽屉里没有 flash 的落点，
        //    吞掉的结果是「点了上传、什么都没发生」。
        if (avatar.isEmpty() || ct == null || !ct.startsWith("image/")) {
            if (hx) {
                throw AppException.validation("请选择图片文件").code("admin.flash.vet.avatarNotImage");
            }
            flash.addFlashAttribute("error", msg.get("admin.flash.vet.avatarNotImage"));
            return "redirect:/admin/vets?open=" + id;
        }
        if (avatar.getSize() > 5L * 1024 * 1024) {
            if (hx) {
                throw AppException.validation("图片过大").code("admin.flash.vet.avatarTooLarge");
            }
            flash.addFlashAttribute("error", msg.get("admin.flash.vet.avatarTooLarge"));
            return "redirect:/admin/vets?open=" + id;
        }
        try {
            adminVetService.updateAvatar(id, avatar.getBytes(), ct, admin.getAdminAccountId());
        } catch (Exception e) {
            // 读文件 IO / OSS 未配置或上传失败（含凭证异常）均优雅回显，不抛 500。
            if (hx) {
                throw AppException.validation("头像上传失败").code("admin.flash.vet.avatarUploadFailed");
            }
            flash.addFlashAttribute("error", msg.get("admin.flash.vet.avatarUploadFailed"));
            return "redirect:/admin/vets?open=" + id;
        }
        if (hx) {
            return vetAfterAction(id, msg.get("admin.flash.vet.avatarUpdated"), "profile", model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.vet.avatarUpdated"));
        return "redirect:/admin/vets?open=" + id;
    }

    @PostMapping("/admin/vets/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.edit') or hasAuthority('vet.create')")
    public String updateVet(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @Valid @ModelAttribute("editVetForm") EditVetForm form, BindingResult binding,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        boolean hx = hxRequest != null;
        if (binding.hasErrors()) {
            // 🔴 校验错误必须**连着表单一起回显**（回的是资料页签本身，不是抽屉全体）：
            //    只回一句错误的话，运营刚填的内容会被换掉，得从头再填一遍。
            if (hx) {
                // 🔴 422 而不是 200：全站的约定是「4xx 出 fragment」（admin-core.js 专门放行
                //    422/403/404）。回 200 的话，任何按状态码判成败的监控与后续自动化
                //    都会把「校验失败」记成成功 —— 界面上看不出，账上全是绿的。
                response.setStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY.value());
                populateVetDrawer(id, model);
                return "admin/fragments/drawer-vets :: profile-form";
            }
            // 非 htmx 兜底（无 JS 的浏览器）：整页 vet-edit 已删，只能 PRG 回列表并开该抽屉。
            // ⚠️ 这条路上字段级错误回显不了 —— 抽屉内容是 htmx 拉的，而这一跳没有 htmx。
            //    可接受：它是降级路径，正常路径（有 JS）走上面那支，错误连着表单一起回显。
            flash.addFlashAttribute("error", msg.get("admin.flash.vet.profileInvalid"));
            return "redirect:/admin/vets?open=" + id;
        }
        try {
            adminVetService.updateProfile(id, form.getDisplayName(), form.getUsername(),
                    form.getContactPhone(), admin.getAdminAccountId());
        } catch (AppException e) {
            if (hx) {
                response.setStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY.value());
                binding.reject("update.failed", msg.resolve(e));
                populateVetDrawer(id, model);
                return "admin/fragments/drawer-vets :: profile-form";
            }
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/vets?open=" + id;
        }
        if (hx) {
            return vetAfterAction(id, msg.get("admin.flash.vet.profileSaved"), "profile", model, response);
        }
        flash.addFlashAttribute("notice", msg.get("admin.flash.vet.profileSaved"));
        return "redirect:/admin/vets?open=" + id;
    }

    @PostMapping("/admin/vets/{id}/password")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.reset_password')")
    public String resetVetPassword(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam("newPassword") String newPassword,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        if (hxRequest != null) {
            // 弱密码等在服务层抛 422，这里不重复判 —— 让它落进抽屉的行内错误槽。
            adminVetService.resetPassword(id, newPassword, admin.getAdminAccountId());
            // 🔴 明文**只此一屏**：放一次性 model 属性随本次响应渲染，
            //    不进 session / flash / 审计 / 日志（既有约束，UI 稿 6-4）。
            model.addAttribute("issuedPassword", newPassword);
            // 🔴 停在「账号」页签：明文就渲染在那里，回默认页签等于把唯一的获取窗口盖住。
            return vetAfterAction(id, msg.get("admin.flash.vet.passwordReset"), "account", model, response);
        }
        try {
            adminVetService.resetPassword(id, newPassword, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.vet.passwordReset"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/vets?open=" + id;
    }

    @PostMapping("/admin/vets/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.ban')")
    public String setVetStatus(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam("banned") boolean banned,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model, jakarta.servlet.http.HttpServletResponse response, RedirectAttributes flash) {
        adminVetService.setBanned(id, banned, admin.getAdminAccountId());
        String toastKey = banned ? "admin.flash.vet.banned" : "admin.flash.vet.unbanned";
        if (hxRequest != null) {
            return vetAfterAction(id, msg.get(toastKey), "account", model, response);
        }
        flash.addFlashAttribute("notice", msg.get(toastKey));
        return "redirect:/admin/vets?open=" + id;
    }

    // ===== Story 2.6：兽医在线状态快照 =====
    // ⛔ 整页 GET /admin/vets/online 已删除（V1.3.0 Story 9.1a · AC5 / D-11）：
    //    在线态与最后在线时间并入兽医列表的列与抽屉资料页签，不再单开一页。
    //    不做旧地址跳转（D-23）；服务层的 onlineSnapshot(...) 保留（别处仍可能用到）。

    // ===== Story 5.6：兽医评分查看（仅运营可见）→ V1.3.0 Story 9.1b 并入抽屉评分页签 =====

    /**
     * 评分页签（Story 9.1b · AC2）。整页 {@code vet-ratings.html} 已删除。
     *
     * <p>🔴 页签**懒加载**：均分明细 + 未评问诊两次聚合只在真的切过去时才做。
     * 非 htmx 直达 → 回列表并开抽屉（这条路径没有同名 POST，但保持与资质页签一致的落点）。
     */
    @GetMapping("/admin/vets/{id}/ratings")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('rating.view')")
    public String vetRatings(@PathVariable long id,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        if (hxRequest == null) {
            return "redirect:/admin/vets?open=" + id;
        }
        model.addAttribute("active", "vets");
        model.addAttribute("vetId", id);
        model.addAttribute("vet", adminVetService.view(id));
        model.addAttribute("ratings", adminVetService.ratings(id));
        model.addAttribute("unrated", adminVetService.unratedConsults(id)); // Story 6.2：未评问诊单列
        return "admin/fragments/drawer-vets :: rating-panel";
    }

    @PostMapping("/admin/seed-post")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')")
    public String publishSeed(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("seedPostForm") SeedPostForm form, BindingResult binding,
            Model model) {
        model.addAttribute("active", "seed");
        seedPostModel(model);
        if (binding.hasErrors()) {
            return "admin/seed-post";
        }
        // 🔴 V1.1.6 Story 12.2：作者改为**表单选择**（数据源是 Story 12.1 的发布身份池）。
        //
        // 原先这里写死为"登录后台账号所关联的官方内容作者身份"，并对未关联的账号内联报错。
        // 那个行为有两个后果：① 运营只能以那一个身份发内容；
        // ② 成长日历（GROWTH_MOMENT）**实际发不出来** —— 它必须绑一份宠物档案，
        //    而那个官方作者账号没有档案，于是"该宠物是否属于所选作者"这条校验必然失败，
        //    且运营从错误文案里看不出原因。
        //
        // 🛡 "不信任客户端 author" 这条原则没放弃，只是换了守法：
        //    服务端在 publishSeed 里校验该账号在身份池内 + 停用状态 + seed.publish_as_real。
        //    ⚠️ `admin.hasOperatorUserId()` 这个前置条件**随之作废** ——
        //    发布身份不再取自后台账号，纯 Lark / STAFF 账号照样能发。
        // 🛡 兜一层 null：`@NotNull` 正常会在 binding 阶段拦住，但**这里不能靠"正常"** ——
        //    authorUserId 是 Long，拆箱成 long 时若为 null 就是 NPE ⇒ 500 白屏，
        //    而运营看到的只是"系统错误"。宁可多一句判断。
        if (form.getAuthorUserId() == null) {
            binding.reject("publish.failed", "请先选择发布账号。");
            model.addAttribute("seedPostForm", form);
            return "admin/seed-post";
        }
        try {
            ContentPostResponse saved = adminContentService.publishSeed(
                    form.getAuthorUserId(), form.getType(), form.getPetId(), form.getText(),
                    form.imageUrls(), form.imageSizes(),
                    com.tailtopia.admin.virtual.service.AdminPublishIdentityService.mayPublishAsReal(admin), form.getSpecies());
            // 发布成功：清空表单 + 成功提示（含 postId，便于运营核对）。
            model.addAttribute("seedPostForm", new SeedPostForm());
            model.addAttribute("publishedId", saved.id());
            return "admin/seed-post";
        } catch (AppException e) {
            // 服务端权威校验失败（类型/字数/图片数/宠物归属）→ 表单内联回显。
            binding.reject("publish.failed", e.getMessage());
            model.addAttribute("seedPostForm", form);
            return "admin/seed-post";
        }
    }
}

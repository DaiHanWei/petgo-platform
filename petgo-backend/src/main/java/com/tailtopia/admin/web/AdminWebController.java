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
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.shared.error.AppException;
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
    private final com.tailtopia.admin.dashboard.service.AdminDashboardService dashboardService;

    public AdminWebController(AdminContentService adminContentService,
            AdminModerationService adminModerationService,
            AdminVetService adminVetService,
            com.tailtopia.admin.dashboard.service.AdminDashboardService dashboardService) {
        this.adminContentService = adminContentService;
        this.adminModerationService = adminModerationService;
        this.adminVetService = adminVetService;
        this.dashboardService = dashboardService;
    }

    /** 登录页（未认证可访问；认证失败回显 error，登出回显 logout）。 */
    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }

    /** 运营概览看板（Story 9.10，AB-1.1-01）：四模块指标聚合（原种子发布引导页升级为概览）。 */
    @GetMapping({"/admin", "/admin/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("active", "dashboard");
        model.addAttribute("metrics", dashboardService.overview());
        return "admin/dashboard";
    }

    @GetMapping("/admin/seed-post")
    public String seedPostForm(Model model) {
        model.addAttribute("active", "seed");
        if (!model.containsAttribute("seedPostForm")) {
            model.addAttribute("seedPostForm", new SeedPostForm());
        }
        model.addAttribute("types", ContentType.values());
        return "admin/seed-post";
    }

    // ===== Story 3.7 + 4.1：举报审核队列（状态筛选 + 批量 + 双向通知 + 审计）=====

    @GetMapping("/admin/reports")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.view_reports')")
    public String reports(@RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest, Model model) {
        com.tailtopia.moderation.domain.ReportStatus st = parseReportStatus(status);
        model.addAttribute("active", "reports");
        model.addAttribute("status", st.name());
        model.addAttribute("reports", adminModerationService.queue(st));
        return hxRequest != null ? "admin/reports :: rows" : "admin/reports";
    }

    private com.tailtopia.moderation.domain.ReportStatus parseReportStatus(String s) {
        if (s == null || s.isBlank()) {
            return com.tailtopia.moderation.domain.ReportStatus.PENDING;
        }
        try {
            return com.tailtopia.moderation.domain.ReportStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return com.tailtopia.moderation.domain.ReportStatus.PENDING;
        }
    }

    @PostMapping("/admin/reports/{id}/takedown")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')")
    public String takedown(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id) {
        adminModerationService.takedown(id, admin);
        return "redirect:/admin/reports";
    }

    @PostMapping("/admin/reports/{id}/dismiss")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.view_reports')")
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id) {
        adminModerationService.dismiss(id, admin);
        return "redirect:/admin/reports";
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
                "批量完成：成功 " + result.ok() + " 条，失败 " + result.failedCount() + " 条");
        return "redirect:/admin/reports";
    }

    // ===== Story 5.1：兽医账号 CRUD（复用本 shell）=====

    @GetMapping("/admin/vets")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.view')")
    public String vets(@RequestParam(value = "accountStatus", required = false) String accountStatus,
            @RequestParam(value = "qualStatus", required = false) String qualStatus,
            @RequestParam(value = "online", required = false) String online,
            @RequestParam(value = "q", required = false) String q,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vets",
                adminVetService.list(new VetListFilter(accountStatus, qualStatus, online, q)));
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
        // HTMX 局部刷新返结果行片段；整页请求返完整视图。
        return hxRequest != null ? "admin/vets :: rows" : "admin/vets";
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
            model.addAttribute("createVetForm", new CreateVetForm());
            model.addAttribute("createdVetId", id);
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
        model.addAttribute("vets", adminVetService.list(VetListFilter.none()));
        model.addAttribute("vetStatuses", VetStatus.values());
        model.addAttribute("qualStatuses", QualificationStatus.values());
        model.addAttribute("expiryStats", adminVetService.qualificationExpiryStats());
    }

    // ===== Story 2.4：编辑兽医资料（不中断会话）=====

    @GetMapping("/admin/vets/{id}/edit")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.create')")
    public String editVetForm(@PathVariable long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vetId", id);
        if (!model.containsAttribute("editVetForm")) {
            model.addAttribute("editVetForm", adminVetService.editForm(id));
        }
        model.addAttribute("currentAvatarUrl", adminVetService.view(id).avatarUrl());
        return "admin/vet-edit";
    }

    /** 上传/更换兽医头像（服务端落公开桶① → 回填 CDN URL）。仅图片、≤5MB。 */
    @PostMapping("/admin/vets/{id}/avatar")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.create')")
    public String uploadVetAvatar(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("avatar") org.springframework.web.multipart.MultipartFile avatar,
            RedirectAttributes flash) {
        String ct = avatar.getContentType();
        if (avatar.isEmpty() || ct == null || !ct.startsWith("image/")) {
            flash.addFlashAttribute("error", "请选择图片文件");
            return "redirect:/admin/vets/" + id + "/edit";
        }
        if (avatar.getSize() > 5L * 1024 * 1024) {
            flash.addFlashAttribute("error", "头像不能超过 5MB");
            return "redirect:/admin/vets/" + id + "/edit";
        }
        try {
            adminVetService.updateAvatar(id, avatar.getBytes(), ct, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已更新兽医头像");
        } catch (Exception e) {
            // 读文件 IO / OSS 未配置或上传失败（含凭证异常）均优雅回显，不抛 500。
            flash.addFlashAttribute("error", "头像上传失败，请重试");
        }
        return "redirect:/admin/vets/" + id + "/edit";
    }

    @PostMapping("/admin/vets/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.create')")
    public String updateVet(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @Valid @ModelAttribute("editVetForm") EditVetForm form, BindingResult binding,
            Model model, RedirectAttributes flash) {
        if (binding.hasErrors()) {
            model.addAttribute("active", "vets");
            model.addAttribute("vetId", id);
            return "admin/vet-edit";
        }
        try {
            adminVetService.updateProfile(id, form.getDisplayName(), form.getUsername(),
                    form.getContactPhone(), admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已保存兽医资料");
            return "redirect:/admin/vets";
        } catch (AppException e) {
            binding.reject("update.failed", e.getMessage());
            model.addAttribute("active", "vets");
            model.addAttribute("vetId", id);
            return "admin/vet-edit";
        }
    }

    @PostMapping("/admin/vets/{id}/password")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.reset_password')")
    public String resetVetPassword(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam("newPassword") String newPassword,
            RedirectAttributes flash) {
        try {
            adminVetService.resetPassword(id, newPassword, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已重置该兽医密码（请将新密码线下交付）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/vets";
    }

    @PostMapping("/admin/vets/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.ban')")
    public String setVetStatus(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long id, @RequestParam("banned") boolean banned, RedirectAttributes flash) {
        adminVetService.setBanned(id, banned, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", banned ? "已封禁该兽医（进行中问诊已中断并通知用户）" : "已解封该兽医");
        return "redirect:/admin/vets";
    }

    // ===== Story 2.6：兽医在线状态快照（只读，手动刷新）=====

    @GetMapping("/admin/vets/online")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('vet.view')")
    public String vetOnline(@RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {
        java.time.Instant now = java.time.Instant.now();
        model.addAttribute("active", "online");
        model.addAttribute("snapshot", adminVetService.onlineSnapshot(now));
        // 最后查询时间按运营时区（Asia/Jakarta = WIB）格式化展示，逻辑仍 UTC。
        model.addAttribute("queriedAtLabel", java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Jakarta")).format(now) + " WIB");
        return hxRequest != null ? "admin/vet-online :: results" : "admin/vet-online";
    }

    // ===== Story 5.6：兽医评分查看（仅运营可见）=====

    @GetMapping("/admin/vets/{id}/ratings")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('rating.view')")
    public String vetRatings(@PathVariable long id, Model model) {
        model.addAttribute("active", "vets");
        model.addAttribute("vet", adminVetService.view(id));
        model.addAttribute("ratings", adminVetService.ratings(id));
        model.addAttribute("unrated", adminVetService.unratedConsults(id)); // Story 6.2：未评问诊单列
        return "admin/vet-ratings";
    }

    @PostMapping("/admin/seed-post")
    public String publishSeed(@AuthenticationPrincipal AdminUserDetails admin,
            @Valid @ModelAttribute("seedPostForm") SeedPostForm form, BindingResult binding,
            Model model) {
        model.addAttribute("active", "seed");
        model.addAttribute("types", ContentType.values());
        if (binding.hasErrors()) {
            return "admin/seed-post";
        }
        // 种子内容 author_id 须为合法 users.id（FK→users）；仅关联官方内容作者行的账号可发，
        // 否则友好内联提示（勿用 admin_account_id 兜底——非 users.id 会破坏 FK）。避免对 STAFF/纯 Lark 账号 500。
        if (!admin.hasOperatorUserId()) {
            binding.reject("publish.failed", "当前后台账号未关联官方内容作者身份，无法发布种子内容（请用官方作者账号发布）。");
            model.addAttribute("seedPostForm", form);
            return "admin/seed-post";
        }
        try {
            ContentPostResponse saved = adminContentService.publishSeed(
                    admin.getUserId(), form.getType(), form.getPetId(), form.getText(), form.imageUrls());
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

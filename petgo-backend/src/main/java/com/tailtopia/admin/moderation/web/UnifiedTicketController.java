package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.moderation.domain.AccountReportEntry;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import com.tailtopia.moderation.service.AccountDisposalService;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 统一工单队列页（Story 3.1，AB-3D）。SSR + HTMX，路由 {@code /admin/tickets}。
 *
 * <p><b>完全替代旧的举报队列 AB-3A</b>（{@code /admin/reports} 已重定向到这里）——
 * 不是两者并存：三类工单混在一个队列里、按同一把尺子排序，运营才不用在几个入口之间来回切、
 * 也不用对着一堆互不可比的标记猜先处理哪个。
 *
 * <p>详情走单独的 HTMX 片段（点「展开」才拉）：签名、每一次举报的类型与补充说明、历史处置记录
 * 都只在展开那一行时查一次，<b>不在列表里逐行查</b>（那就是既有举报队列的 N+1）。
 */
@Controller
public class UnifiedTicketController {

    /** 本页作用域：只有用户举报（2026-08-19 拆分）。 */
    private static final java.util.Set<TicketType> SCOPE =
            java.util.EnumSet.of(TicketType.ACCOUNT_REPORT);

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.view_tickets')";

    /** 警告 / 判为无需处置：只要处置权。 */
    static final String DISPOSE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('content.dispose_account')";

    /**
     * ⚠️ 封号<b>额外</b>要 {@code user.deactivate}（and 关系）。
     * 停用账号本来就是一项受管能力，不能因为「他能处理工单」就顺带把停用权也给了。
     */
    static final String SUSPEND_AUTH = "hasRole('SUPER_ADMIN') or "
            + "(hasAuthority('content.dispose_account') and hasAuthority('user.deactivate'))";

    private static final int PAGE_SIZE = 20;

    private final UnifiedTicketQueryService query;
    private final AccountReportEntryRepository reportEntries;
    private final AccountDisposalRepository disposals;
    private final AccountQueryService accountQueryService;
    private final AccountDisposalService disposalService;
    private final com.tailtopia.moderation.service.ReportService contentReportService;
    private final com.tailtopia.admin.service.AdminModerationService moderationService;

    public UnifiedTicketController(UnifiedTicketQueryService query,
            AccountReportEntryRepository reportEntries, AccountDisposalRepository disposals,
            AccountQueryService accountQueryService, AccountDisposalService disposalService,
            com.tailtopia.moderation.service.ReportService contentReportService,
            com.tailtopia.admin.service.AdminModerationService moderationService) {
        this.query = query;
        this.reportEntries = reportEntries;
        this.disposals = disposals;
        this.accountQueryService = accountQueryService;
        this.disposalService = disposalService;
        this.contentReportService = contentReportService;
        this.moderationService = moderationService;
    }

    @GetMapping("/admin/tickets")
    @PreAuthorize(VIEW_AUTH)
    public String tickets(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestHeader(value = "HX-Request", required = false) String hxRequest,
            Model model) {

        TicketStatusBucket statusFilter = parseEnum(TicketStatusBucket.class, status);
        // 🔴 2026-08-19 拆分：本页**只管用户举报**，标题改为「被举报用户」。
        // 内容举报与账号标识字段已移入「人工复核」页 —— 它们的处置动作和授权域都不在本页，
        // 之前挤在一起的结果是：账号标识字段那一类在本页根本无法处置（点批量只吃一条红字提示）。
        Page<UnifiedTicketRow> result = query.search(SCOPE, null, statusFilter, q,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE));

        model.addAttribute("active", "tickets");
        model.addAttribute("result", result);
        model.addAttribute("statuses", TicketStatusBucket.values());
        // 回显筛选条件（分页链接要带着它们走）。类别下拉已移除（本页恒为用户举报一类）。
        model.addAttribute("type", null);
        model.addAttribute("status", statusFilter == null ? null : statusFilter.name());
        model.addAttribute("q", q);

        return hxRequest != null ? "admin/tickets :: resultsFragment" : "admin/tickets";
    }

    /**
     * 一条工单的展开详情（HTMX 片段）。
     *
     * <p>⚠️ 这里读的三样东西都<b>只在展开时查一次</b>：
     * <ul>
     *   <li><b>个性签名</b>：举报「仿冒他人」「持续骚扰」时，签名往往<b>就是证据本身</b>
     *       （冒充某人的自我介绍、指名道姓的攻击），运营不该还要再跳一步去别处看。
     *       取<b>当前值</b>（经既有账号查询服务），不快照、不落工单表。</li>
     *   <li><b>历史处置记录</b>：含<b>每一次警告</b>——只数封号会漏掉「已经被警告过三次」这种关键背景。</li>
     *   <li><b>每一次举报的类型与「其他」补充说明</b>，按时间倒序（第一次报骚扰、第二次报仿冒，
     *       本身就是问题在升级的证据）。</li>
     * </ul>
     */
    /**
     * 展开面板。**两个页面共用**：被举报用户（用户举报）与人工复核（内容举报）。
     *
     * <p>🔴 权限比列表页的 {@code VIEW_AUTH} 宽一档：加上 {@code content.manual_review} 与
     * {@code content.takedown}。因为内容举报 2026-08-19 拆到了人工复核页，而那页的入口认的是
     * 后两个码 —— 若这里仍只认 {@code content.view_tickets}，只持 takedown 的审核员
     * <b>页面打得开、点「展开」却吃 403</b>：面板一片空白、没有任何提示，
     * 只能当成「这个功能坏了」。
     */
    @GetMapping("/admin/tickets/detail")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.view_tickets') "
            + "or hasAuthority('content.manual_review') or hasAuthority('content.takedown')")
    public String detail(@RequestParam("type") String type,
            @RequestParam("sourceId") long sourceId,
            @RequestParam(value = "userId", required = false) Long userId,
            Model model) {
        TicketType ticketType = parseEnum(TicketType.class, type);
        model.addAttribute("type", ticketType == null ? null : ticketType.name());
        model.addAttribute("sourceId", sourceId);

        // 修复清单 #3：内容举报的「每一次举报」也要能看（原因 + 时间）——旧 /admin/reports 页
        // 下线后这里是唯一可见处。两类映射成同一形状（reason/createdAt/detail）复用同一段模板。
        // 每一行都带上**举报人**（2026-08-20）：处置一条举报时，「谁在报」和「报的什么理由」
        // 一样是判断依据 —— 三个不同的人各报一次与一个人反复报三次，处置结论可能完全相反，
        // 而优先级分只把这件事压成一个数字。原先这里只给原因 + 时间，运营看不出是谁。
        // ⚠️ 昵称**批量解析一次**（findAuthorViews 收一组 id）。逐条查昵称就是 N+1，
        //    而这个面板是逐行展开、每次都会打一遍。
        List<ReportEntryView> entries;
        if (ticketType == TicketType.ACCOUNT_REPORT) {
            var rows = reportEntries.findByReportIdOrderByCreatedAtDesc(sourceId);
            var names = nicknamesOf(rows.stream().map(e -> e.getReporterId()).toList());
            entries = rows.stream()
                    .map(e -> new ReportEntryView(e.getReporterId(), names.get(e.getReporterId()),
                            e.getReason().name(), e.getCreatedAt(), e.getDetail()))
                    .toList();
        } else if (ticketType == TicketType.CONTENT_REPORT) {
            var rows = contentReportService.findAllForPost(sourceId);
            var names = nicknamesOf(rows.stream().map(r -> r.getReporterId()).toList());
            entries = rows.stream()
                    .map(r -> new ReportEntryView(r.getReporterId(), names.get(r.getReporterId()),
                            r.getReasonType().name(), r.getCreatedAt(), null))
                    .toList();
        } else {
            entries = List.of();
        }
        model.addAttribute("entries", entries);
        // 多于 1 条时页面只显示「原因 × 次数」，逐条明细折叠起来（2026-08-20 产品口径）：
        // 十几个人举报同一个对象时，逐条列出来的那一屏没人会一行行读，真正要看的是「都在报什么」。
        // 次数倒序、同次数按原因名稳定排序 —— 别让同一份数据两次打开顺序不同。
        model.addAttribute("reasonCounts", entries.stream()
                .collect(java.util.stream.Collectors.groupingBy(ReportEntryView::reason,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(e -> new ReasonCount(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparingLong(ReasonCount::count).reversed()
                        .thenComparing(ReasonCount::reason))
                .toList());

        if (userId != null) {
            model.addAttribute("signature", accountQueryService.activeSignatureOf(userId).orElse(null));
            model.addAttribute("disposals", disposals.findByTargetUserIdOrderByCreatedAtDesc(userId));
        } else {
            model.addAttribute("signature", null);
            model.addAttribute("disposals", List.of());
        }
        return "admin/tickets :: detailFragment";
    }

    // ===== Story 3.2：账号级处置 =====
    //
    // 三个动作都是**真表单 POST**（后台 CSRF 开着，AJAX 那套这里不适用），处理完 redirect 回列表。
    // ⚠️ 「限流曝光」这一档**不实现、也不留任何 UI 位** —— 它依赖推荐算法打分链路，随 FR-95 移到 1.1.8。
    //    留一个点了没反应的按钮比没有按钮更糟。

    /** 警告：发一条通知 + 记一行处置，**不影响用户使用**。 */
    @PostMapping("/admin/tickets/warn")
    @PreAuthorize(DISPOSE_AUTH)
    public String warn(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("targetUserId") long targetUserId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            RedirectAttributes flash) {
        return withFlashOnError(flash, "已警告该账号",
                () -> disposalService.warn(targetUserId, reportId, admin.getAdminAccountId()));
    }

    /** 封号：停用账号（可逆）+ 撤销 refresh 句柄 + 发通知 + 记一行处置。 */
    @PostMapping("/admin/tickets/suspend")
    @PreAuthorize(SUSPEND_AUTH)
    public String suspend(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("targetUserId") long targetUserId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            RedirectAttributes flash) {
        return withFlashOnError(flash, "已停用该账号",
                () -> disposalService.suspend(targetUserId, reportId, admin.getAdminAccountId()));
    }

    /** 无需处置：工单收档，**对被举报账号什么都不做**（举报人仍收 FR-51 模糊回告）。 */
    @PostMapping("/admin/tickets/dismiss")
    @PreAuthorize(DISPOSE_AUTH)
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("reportId") long reportId, RedirectAttributes flash) {
        return withFlashOnError(flash, "已标记为无需处置",
                () -> disposalService.dismiss(reportId, admin.getAdminAccountId()));
    }

    /**
     * 单条处置的业务失败（工单不匹配 / 用户不存在 / 已被并发处理）走 flash 回列表——
     * 运营端不该为一次陈旧表单吃整页 500。
     *
     * <p>⚠️ 失败写 {@code error}（红色横幅）而非 {@code notice}（绿色成功横幅）——评审三轮 #9：
     * 把「工单不匹配」塞进 notice 会让运营把失败读成封号成功。
     */
    private static String withFlashOnError(RedirectAttributes flash, String successNotice,
            Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("notice", successNotice);
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/tickets";
    }

    // ===== Story 3.3：批量处置 =====

    /**
     * 批量处置（AC1–AC6）。**真表单 POST**（CSRF 开着，fetch/XHR 那套这里不适用）。
     *
     * <p>勾选框的 value 是 <b>{@code 类型:id} 的复合串</b>（如 {@code ACCOUNT_REPORT:12}）——
     * 光有 id 是<b>分辨不出类型</b>的：内容举报工单的 sourceId 是帖子 id、账号举报是工单 id、
     * 标识字段是审核记录 id，三者的数字空间会重叠。带上类型，跨类型混选才能在服务端被识别并<b>整批拒绝</b>。
     *
     * <p>⚠️ <b>前端的置灰只是体验，这里的校验才是边界</b>：勾选框在浏览器里可以随便改，
     * 「一次别封掉几百个人」不能只靠前端。
     */
    @PostMapping("/admin/tickets/batch")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('content.dispose_account')"
            + " or hasAuthority('content.takedown')")
    public String batch(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("action") String action,
            @RequestParam(value = "ticketIds", required = false) List<String> ticketIds,
            RedirectAttributes flash) {
        ParsedBatch parsed;
        try {
            parsed = parseBatch(ticketIds);
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/tickets";
        }

        // 按工单类型分派（修复清单二轮 #7：旧 /admin/reports 页下线后，内容举报的批量能力
        // 必须在统一队列接住，不能整体回退成逐条点）。权限矩阵按分支各自收口——
        // 账号处置权与内容下架权是两套授权域，端点级 gate 只做「至少持有其一」的粗筛。
        // ⚠️ 分支内的「持有另一类权限但没有本类权限」不能抛 AccessDeniedException（评审三轮 #6）——
        // 「批量无需处置」按钮对两类工单共用、且只要有其一即渲染，抛异常=运营点渲染出来的按钮吃整页 403；
        // 一律降级为红色 flash 提示。
        // 2026-08-19 拆分后本页只渲染用户举报，另两类不会从这里提交；
        // 但表单是可以被手改的，仍如实给出去处，绝不 500、也绝不误处置成别的类型。
        return switch (parsed.type()) {
            case ACCOUNT_REPORT -> batchAccountReports(admin, action, parsed.ids(), flash);
            case CONTENT_REPORT, ACCOUNT_IDENTITY, CONTENT_SUBMISSION -> {
                flash.addFlashAttribute("error", "该类工单请在「人工复核」页处理");
                yield "redirect:/admin/tickets";
            }
        };
    }

    private String batchAccountReports(AdminUserDetails admin, String action, List<Long> reportIds,
            RedirectAttributes flash) {
        if (!hasAnyAuthority("content.dispose_account")) {
            flash.addFlashAttribute("error", "你没有处置用户举报工单的权限");
            return "redirect:/admin/tickets";
        }
        AccountDisposalService.BatchAction batchAction = parseEnum(
                AccountDisposalService.BatchAction.class, action);
        if (batchAction == null) {
            flash.addFlashAttribute("error", "用户举报工单支持：批量警告 / 批量封号 / 批量无需处置");
            return "redirect:/admin/tickets";
        }
        // 封号那一档额外要 user.deactivate —— 与单条口径一致，别让批量成为绕过它的后门。
        // 封号按钮在模板已按 user.deactivate 隐藏，走到这里只可能是篡改，红色提示即可（不 500）。
        if (batchAction == AccountDisposalService.BatchAction.SUSPEND && !canSuspend()) {
            flash.addFlashAttribute("error", "你没有停用账号的权限");
            return "redirect:/admin/tickets";
        }

        AccountDisposalService.BatchResult result;
        try {
            result = disposalService.batch(reportIds, batchAction, admin.getAdminAccountId());
        } catch (AppException e) {
            // 超 50 条上限等整批校验失败：给 flash 提示回列表，不能让运营吃一个整页错误
            //（前端置灰只是体验，勾选框在浏览器里可以随便改）。
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/tickets";
        }
        flash.addFlashAttribute("notice",
                "批量完成：成功 " + result.ok() + " 条，失败 " + result.failedCount() + " 条");
        // ⚠️ 失败明细必须真的渲染出来（AC5）：只报数量的话运营不知道是哪几条、为什么，也就无从重试。
        flash.addFlashAttribute("batchFailures", result.failed());
        return "redirect:/admin/tickets";
    }

    /** 内容举报批量：动作只有下架 / 驳回（DISMISS 按钮在内容语境下就是驳回），gate 对齐旧批量的 takedown。 */
    private String batchContentReports(AdminUserDetails admin, String action, List<Long> postIds,
            RedirectAttributes flash) {
        boolean takedown = "TAKEDOWN".equals(action);
        if (!takedown && !"DISMISS".equals(action)) {
            flash.addFlashAttribute("error", "内容举报工单支持：批量下架 / 批量无需处置（驳回）");
            return "redirect:/admin/tickets";
        }
        if (!hasAnyAuthority("content.takedown")) {
            flash.addFlashAttribute("error", "你没有处置内容举报工单的权限");
            return "redirect:/admin/tickets";
        }
        if (postIds.size() > AccountDisposalService.MAX_BATCH_SIZE) {
            flash.addFlashAttribute("error",
                    "单次最多处理 " + AccountDisposalService.MAX_BATCH_SIZE + " 条工单");
            return "redirect:/admin/tickets";
        }
        com.tailtopia.admin.service.AdminModerationService.BatchResult result =
                moderationService.batchByPost(postIds, takedown, admin);
        flash.addFlashAttribute("notice",
                "批量完成：成功 " + result.ok() + " 条，失败 " + result.failedCount() + " 条");
        flash.addFlashAttribute("batchFailures", result.failed());
        return "redirect:/admin/tickets";
    }

    /** 一批同类型工单：类型 + 源表 id 列表（内容举报是 postId、账号举报是工单 id）。 */
    private record ParsedBatch(TicketType type, List<Long> ids) {
    }

    /**
     * 解析 {@code 类型:id} 复合串，并把 AC2 的边界钉在服务端：<b>跨类型整批拒绝</b>。
     *
     * <p>为什么跨类型不能批：不同类型工单的处置对象含义根本不同 ——
     * 内容举报处置的是<b>内容</b>，账号举报处置的是<b>人</b>。混在一批里执行同一个动作没有意义。
     * 同类型批次放行，动作合法性由各类型分支自行判定。
     */
    private static ParsedBatch parseBatch(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw AppException.validation("请先勾选要处理的工单");
        }
        TicketType batchType = null;
        List<Long> ids = new java.util.ArrayList<>(tokens.size());
        for (String token : tokens) {
            int sep = token.indexOf(':');
            if (sep <= 0) {
                throw AppException.validation("工单标识格式不正确");
            }
            TicketType type = parseEnum(TicketType.class, token.substring(0, sep));
            if (type == null) {
                throw AppException.validation("工单标识格式不正确");
            }
            if (batchType == null) {
                batchType = type;
            } else if (batchType != type) {
                throw AppException.validation("不同类型的工单不能一起批量处理");
            }
            try {
                ids.add(Long.parseLong(token.substring(sep + 1)));
            } catch (NumberFormatException e) {
                throw AppException.validation("工单标识格式不正确");
            }
        }
        return new ParsedBatch(batchType, ids);
    }

    private static boolean canSuspend() {
        return hasAnyAuthority("user.deactivate");
    }

    /** 持有任一指定权限码，或 SUPER_ADMIN（全后台通例：SUPER_ADMIN 覆盖一切权限点）。 */
    private static boolean hasAnyAuthority(String... names) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> {
            String granted = a.getAuthority();
            if ("ROLE_SUPER_ADMIN".equals(granted)) {
                return true;
            }
            for (String name : names) {
                if (granted.equals(name)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * 展开详情「每一次举报」的统一行形状：账号举报直接用实体（reason/createdAt/detail 同名），
     * 内容举报映射到本 record —— 两类共用模板同一段循环。
     */
    /**
     * 「原因 × 次数」的一行（多于 1 条举报时的聚合摘要）。
     *
     * @param reason 举报原因枚举名
     * @param count  该原因被报了多少**次**（不是多少人）—— 人数/次数/高频人数三个数在**行上**
     *               已经分列给出，这里只回答「都在报什么」
     */
    public record ReasonCount(String reason, long count) {
    }

    /**
     * 展开面板里「每一次举报」的一行。两类举报映射成同一形状复用同一段模板。
     *
     * @param reporterId       举报人账号 id。⚠️ 只在运营后台展示 —— <b>绝不下发给被举报人、
     *                         也绝不进日志</b>（举报人身份一旦外泄，被举报者就能定点报复，
     *                         举报功能等于废掉）
     * @param reporterNickname 举报人当前昵称；注销 / 查不到时为 null，模板显示为「账号已注销」
     * @param detail           举报人填的补充说明（账号举报有，内容举报没有这个字段）
     */
    public record ReportEntryView(Long reporterId, String reporterNickname, String reason,
            java.time.Instant createdAt, String detail) {
    }

    /**
     * 一批账号 id → 昵称。注销或查不到的**不放进 map**（模板据此显示「账号已注销」）。
     *
     * <p>一次查完，不在循环里逐条查 —— 展开面板每次都会打这一遍。
     */
    private java.util.Map<Long, String> nicknamesOf(List<Long> ids) {
        var distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> out = new java.util.HashMap<>();
        accountQueryService.findAuthorViews(distinct).forEach((id, view) -> {
            if (view != null && !view.deleted() && view.nickname() != null) {
                out.put(id, view.nickname());
            }
        });
        return out;
    }

    /** 空白 / 非法值一律当「不筛选」，不给运营一个 400。 */
    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

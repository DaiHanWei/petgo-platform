package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.dto.TicketFilters;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.service.TicketsWorkbenchService;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.moderation.service.AccountDisposalService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 统一工单队列页（Story 3.1，AB-3D）。SSR + HTMX，路由 {@code /admin/tickets}。
 *
 * <p><b>完全替代旧的举报队列 AB-3A</b>（{@code /admin/reports} 已重定向到这里）——
 * 不是两者并存：三类工单混在一个队列里、按同一把尺子排序，运营才不用在几个入口之间来回切、
 * 也不用对着一堆互不可比的标记猜先处理哪个。
 *
 * <p>详情走单独的 HTMX 片段（选中才拉）：签名、每一次举报的类型与补充说明、历史处置记录
 * 都只在选中那一条时查一次，<b>不在列表里逐行查</b>（那就是既有举报队列的 N+1）。
 *
 * <p>V1.3.0 Story 2.5：重构为模板 A 双栏工作台（{@code tpl-a-workbench}）——两态页签 / 左栏队列 / 右栏四区 /
 * 两段式处置区。<b>写端点路径 / 参数 / 权限零变更</b>（唯一例外：{@code warn} 新增必填 {@code reason}，只进审计）；
 * htmx 请求按 {@link HxRequest} 分叉返回 fragment，非 htmx 维持 PRG。旧 {@code GET /admin/tickets/detail} 退役，
 * 并入 {@code GET /admin/tickets/{id}/detail}。
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

    private final AccountDisposalService disposalService;
    private final com.tailtopia.admin.service.AdminModerationService moderationService;
    /** V1.3.0 Story 2.5：工作台只读装配（两态计数 / 队列 / 四区 / 下一条）。 */
    private final TicketsWorkbenchService workbench;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public UnifiedTicketController(AccountDisposalService disposalService,
            com.tailtopia.admin.service.AdminModerationService moderationService,
            TicketsWorkbenchService workbench, Messages msg) {
        this.disposalService = disposalService;
        this.moderationService = moderationService;
        this.workbench = workbench;
        this.msg = msg;
    }

    /**
     * 工作台整页（V1.3.0 Story 2.5，模板 A）。参数：{@code state}（pending/handled，默认待处置）、{@code reason}（举报类型）、
     * {@code q}（账号 id / 昵称）、{@code page}。旧链接 {@code ?status=RESOLVED} 映射到已处置态；{@code type} 参数已无意义（本页恒为用户举报）。
     * htmx 请求（滚动翻页）只回左栏行片段。
     */
    @GetMapping("/admin/tickets")
    @PreAuthorize(VIEW_AUTH)
    public String tickets(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model) {
        model.addAttribute("active", "tickets");
        populateQueue(TicketFilters.of(state, status, reason, q, page), model);
        return hx.isHtmx() ? "admin/fragments/tickets-queue :: rows" : "admin/tickets";
    }

    /** 左栏队列 fragment（切两态 / 筛选 / 滚动翻页）。 */
    @GetMapping("/admin/tickets/queue")
    @PreAuthorize(VIEW_AUTH)
    public String queueFragment(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            Model model) {
        populateQueue(TicketFilters.of(state, null, reason, q, page), model);
        return page > 0 ? "admin/fragments/tickets-queue :: rows" : "admin/fragments/tickets-queue :: list";
    }

    /**
     * 右栏四区 fragment（AC3 / AC9）：账号卡（头像 / 昵称 / 注册时间 / 签名 / 历史处置，含<b>每一次警告</b>）、举报明细逐条
     * （高频举报人打标）、近期内容抽样、操作区。取代旧 {@code GET /admin/tickets/detail}（已退役）。
     * ⚠️ 举报人身份只在运营后台展示——绝不下发给被举报人、也绝不进日志。
     */
    @GetMapping("/admin/tickets/{id:\\d+}/detail")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable("id") long reportId, Model model) {
        model.addAttribute("d", workbench.detail(reportId));
        return "admin/fragments/tickets-detail :: detail";
    }

    private void populateQueue(TicketFilters filters, Model model) {
        model.addAttribute("filters", filters);
        model.addAttribute("counts", workbench.counts(filters));
        model.addAttribute("queue", workbench.queue(filters));
        model.addAttribute("reasons", com.tailtopia.moderation.domain.AccountReportReason.values());
    }

    // ===== Story 3.2：账号级处置 =====
    //
    // 三个动作都是**真表单 POST**（后台 CSRF 开着，AJAX 那套这里不适用），处理完 redirect 回列表。
    // ⚠️ 「限流曝光」这一档**不实现、也不留任何 UI 位** —— 它依赖推荐算法打分链路，随 FR-95 移到 1.1.8。
    //    留一个点了没反应的按钮比没有按钮更糟。

    /**
     * 警告：发一条通知 + 记一行处置，**不影响用户使用**。
     *
     * <p>V1.3.0 Story 2.5 AC6：新增必填 {@code reason}（PRD §5 ③ ①），<b>只进审计</b>，通知文案一字不改。
     * {@code required=false} + 服务端判空 → 422（缺参给 400 会绕过行内 err 范式）。
     */
    @PostMapping("/admin/tickets/warn")
    @PreAuthorize(DISPOSE_AUTH)
    public String warn(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("targetUserId") long targetUserId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            @RequestParam(value = "reason", required = false) String reason,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // htmx 分支不 try/catch：422 / 403 由 AdminBusinessExceptionAdvice 出 fragment（AC8）。
            disposalService.warn(targetUserId, reportId, admin.getAdminAccountId(), requireReason(reason));
            return done(hx, reportId, msg.get("admin.flash.ticket.warned"), model, response);
        }
        return withFlashOnError(flash, msg.get("admin.flash.ticket.warned"),
                () -> disposalService.warn(targetUserId, reportId, admin.getAdminAccountId(), requireReason(reason)));
    }

    /** 封号：停用账号（可逆）+ 撤销 refresh 句柄 + 发通知 + 记一行处置。 */
    @PostMapping("/admin/tickets/suspend")
    @PreAuthorize(SUSPEND_AUTH)
    public String suspend(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("targetUserId") long targetUserId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            disposalService.suspend(targetUserId, reportId, admin.getAdminAccountId());
            return done(hx, reportId, msg.get("admin.flash.ticket.suspended"), model, response);
        }
        return withFlashOnError(flash, msg.get("admin.flash.ticket.suspended"),
                () -> disposalService.suspend(targetUserId, reportId, admin.getAdminAccountId()));
    }

    /** 无需处置：工单收档，**对被举报账号什么都不做**（举报人仍收 FR-51 模糊回告）。 */
    @PostMapping("/admin/tickets/dismiss")
    @PreAuthorize(DISPOSE_AUTH)
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("reportId") long reportId,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            disposalService.dismiss(reportId, admin.getAdminAccountId());
            return done(hx, reportId, msg.get("admin.flash.ticket.dismissed"), model, response);
        }
        return withFlashOnError(flash, msg.get("admin.flash.ticket.dismissed"),
                () -> disposalService.dismiss(reportId, admin.getAdminAccountId()));
    }

    /** 处置成功 fragment（AC8）：data-next-id + 行 oob 删除 + 两态计数 oob + toast + HX-Trigger 刷角标。 */
    private String done(HxRequest hx, Long reportId, String message, Model model, HttpServletResponse response) {
        model.addAttribute("done", workbench.afterDispose(hx.currentUrl(), reportId == null ? 0L : reportId));
        model.addAttribute("message", message);
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/tickets-done :: done";
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw AppException.validation("警告必须填写理由").code("admin.err.tickets.warnReasonRequired");
        }
        return reason.trim();
    }

    /**
     * 单条处置的业务失败（工单不匹配 / 用户不存在 / 已被并发处理）走 flash 回列表——
     * 运营端不该为一次陈旧表单吃整页 500。
     *
     * <p>⚠️ 失败写 {@code error}（红色横幅）而非 {@code notice}（绿色成功横幅）——评审三轮 #9：
     * 把「工单不匹配」塞进 notice 会让运营把失败读成封号成功。
     */
    private String withFlashOnError(RedirectAttributes flash, String successNotice,
            Runnable action) {
        try {
            action.run();
            flash.addFlashAttribute("notice", successNotice);
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
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
            flash.addFlashAttribute("error", msg.resolve(e));
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
                flash.addFlashAttribute("error", msg.get("admin.flash.ticket.useManualReviewPage"));
                yield "redirect:/admin/tickets";
            }
        };
    }

    private String batchAccountReports(AdminUserDetails admin, String action, List<Long> reportIds,
            RedirectAttributes flash) {
        if (!hasAnyAuthority("content.dispose_account")) {
            flash.addFlashAttribute("error",
                    msg.get("admin.flash.ticket.noAccountDisposePermission"));
            return "redirect:/admin/tickets";
        }
        AccountDisposalService.BatchAction batchAction = parseEnum(
                AccountDisposalService.BatchAction.class, action);
        if (batchAction == null) {
            flash.addFlashAttribute("error", msg.get("admin.flash.ticket.accountBatchActions"));
            return "redirect:/admin/tickets";
        }
        // V1.3.0 Story 2.5 AC6：警告必须写理由，批量条已不渲染批量警告；伪造表单提交一律红字拒绝（服务层重载保留兼容）。
        if (batchAction == AccountDisposalService.BatchAction.WARN) {
            flash.addFlashAttribute("error", msg.get("admin.v130.tickets.batch.warnRetired"));
            return "redirect:/admin/tickets";
        }
        // 封号那一档额外要 user.deactivate —— 与单条口径一致，别让批量成为绕过它的后门。
        // 封号按钮在模板已按 user.deactivate 隐藏，走到这里只可能是篡改，红色提示即可（不 500）。
        if (batchAction == AccountDisposalService.BatchAction.SUSPEND && !canSuspend()) {
            flash.addFlashAttribute("error", msg.get("admin.flash.ticket.noSuspendPermission"));
            return "redirect:/admin/tickets";
        }

        AccountDisposalService.BatchResult result;
        try {
            result = disposalService.batch(reportIds, batchAction, admin.getAdminAccountId());
        } catch (AppException e) {
            // 超 50 条上限等整批校验失败：给 flash 提示回列表，不能让运营吃一个整页错误
            //（前端置灰只是体验，勾选框在浏览器里可以随便改）。
            flash.addFlashAttribute("error", msg.resolve(e));
            return "redirect:/admin/tickets";
        }
        flash.addFlashAttribute("notice",
                msg.get("admin.flash.seed.batchDone", result.ok(), result.failedCount()));
        // ⚠️ 失败明细必须真的渲染出来（AC5）：只报数量的话运营不知道是哪几条、为什么，也就无从重试。
        flash.addFlashAttribute("batchFailures", result.failed());
        return "redirect:/admin/tickets";
    }

    /** 内容举报批量：动作只有下架 / 驳回（DISMISS 按钮在内容语境下就是驳回），gate 对齐旧批量的 takedown。 */
    private String batchContentReports(AdminUserDetails admin, String action, List<Long> postIds,
            RedirectAttributes flash) {
        boolean takedown = "TAKEDOWN".equals(action);
        if (!takedown && !"DISMISS".equals(action)) {
            flash.addFlashAttribute("error", msg.get("admin.flash.ticket.contentBatchActions"));
            return "redirect:/admin/tickets";
        }
        if (!hasAnyAuthority("content.takedown")) {
            flash.addFlashAttribute("error", msg.get("admin.flash.ticket.noContentDisposePermission"));
            return "redirect:/admin/tickets";
        }
        if (postIds.size() > AccountDisposalService.MAX_BATCH_SIZE) {
            flash.addFlashAttribute("error", msg.get("admin.flash.ticket.batchTooLarge",
                    AccountDisposalService.MAX_BATCH_SIZE));
            return "redirect:/admin/tickets";
        }
        com.tailtopia.admin.service.AdminModerationService.BatchResult result =
                moderationService.batchByPost(postIds, takedown, admin);
        flash.addFlashAttribute("notice",
                msg.get("admin.flash.seed.batchDone", result.ok(), result.failedCount()));
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
            throw AppException.validation("请先勾选要处理的工单").code("admin.err.ticket.noneSelected");
        }
        TicketType batchType = null;
        List<Long> ids = new java.util.ArrayList<>(tokens.size());
        for (String token : tokens) {
            int sep = token.indexOf(':');
            if (sep <= 0) {
                throw AppException.validation("工单标识格式不正确").code("admin.err.ticket.badToken");
            }
            TicketType type = parseEnum(TicketType.class, token.substring(0, sep));
            if (type == null) {
                throw AppException.validation("工单标识格式不正确").code("admin.err.ticket.badToken");
            }
            if (batchType == null) {
                batchType = type;
            } else if (batchType != type) {
                throw AppException.validation("不同类型的工单不能一起批量处理")
                        .code("admin.err.ticket.mixedTypes");
            }
            try {
                ids.add(Long.parseLong(token.substring(sep + 1)));
            } catch (NumberFormatException e) {
                throw AppException.validation("工单标识格式不正确").code("admin.err.ticket.badToken");
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

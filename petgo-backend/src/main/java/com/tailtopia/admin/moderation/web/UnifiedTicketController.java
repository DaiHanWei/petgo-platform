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

    public UnifiedTicketController(UnifiedTicketQueryService query,
            AccountReportEntryRepository reportEntries, AccountDisposalRepository disposals,
            AccountQueryService accountQueryService, AccountDisposalService disposalService) {
        this.query = query;
        this.reportEntries = reportEntries;
        this.disposals = disposals;
        this.accountQueryService = accountQueryService;
        this.disposalService = disposalService;
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

        TicketType typeFilter = parseEnum(TicketType.class, type);
        TicketStatusBucket statusFilter = parseEnum(TicketStatusBucket.class, status);
        Page<UnifiedTicketRow> result =
                query.search(typeFilter, statusFilter, q, PageRequest.of(Math.max(page, 0), PAGE_SIZE));

        model.addAttribute("active", "tickets");
        model.addAttribute("result", result);
        model.addAttribute("types", TicketType.values());
        model.addAttribute("statuses", TicketStatusBucket.values());
        // 回显筛选条件（分页链接要带着它们走）。
        model.addAttribute("type", typeFilter == null ? null : typeFilter.name());
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
    @GetMapping("/admin/tickets/detail")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@RequestParam("type") String type,
            @RequestParam("sourceId") long sourceId,
            @RequestParam(value = "userId", required = false) Long userId,
            Model model) {
        TicketType ticketType = parseEnum(TicketType.class, type);
        model.addAttribute("type", ticketType == null ? null : ticketType.name());
        model.addAttribute("sourceId", sourceId);

        List<AccountReportEntry> entries = ticketType == TicketType.ACCOUNT_REPORT
                ? reportEntries.findByReportIdOrderByCreatedAtDesc(sourceId)
                : List.of();
        model.addAttribute("entries", entries);

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
        disposalService.warn(targetUserId, reportId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已警告该账号");
        return "redirect:/admin/tickets";
    }

    /** 封号：停用账号（可逆）+ 撤销 refresh 句柄 + 发通知 + 记一行处置。 */
    @PostMapping("/admin/tickets/suspend")
    @PreAuthorize(SUSPEND_AUTH)
    public String suspend(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("targetUserId") long targetUserId,
            @RequestParam(value = "reportId", required = false) Long reportId,
            RedirectAttributes flash) {
        disposalService.suspend(targetUserId, reportId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已停用该账号");
        return "redirect:/admin/tickets";
    }

    /** 无需处置：工单收档，**对被举报账号什么都不做、也不发任何通知**。 */
    @PostMapping("/admin/tickets/dismiss")
    @PreAuthorize(DISPOSE_AUTH)
    public String dismiss(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("reportId") long reportId, RedirectAttributes flash) {
        disposalService.dismiss(reportId, admin.getAdminAccountId());
        flash.addFlashAttribute("notice", "已标记为无需处置");
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
    @PreAuthorize(DISPOSE_AUTH)
    public String batch(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("action") String action,
            @RequestParam(value = "ticketIds", required = false) List<String> ticketIds,
            RedirectAttributes flash) {
        AccountDisposalService.BatchAction batchAction = parseEnum(
                AccountDisposalService.BatchAction.class, action);
        if (batchAction == null) {
            flash.addFlashAttribute("notice", "未知的批量动作");
            return "redirect:/admin/tickets";
        }
        // 封号那一档额外要 user.deactivate —— 与单条口径一致，别让批量成为绕过它的后门。
        if (batchAction == AccountDisposalService.BatchAction.SUSPEND && !canSuspend()) {
            throw new org.springframework.security.access.AccessDeniedException("缺少停用账号权限");
        }

        List<Long> reportIds;
        try {
            reportIds = parseAccountReportIds(ticketIds);
        } catch (AppException e) {
            flash.addFlashAttribute("notice", e.getMessage());
            return "redirect:/admin/tickets";
        }

        AccountDisposalService.BatchResult result =
                disposalService.batch(reportIds, batchAction, admin.getAdminAccountId());
        flash.addFlashAttribute("notice",
                "批量完成：成功 " + result.ok() + " 条，失败 " + result.failedCount() + " 条");
        // ⚠️ 失败明细必须真的渲染出来（AC5）：只报数量的话运营不知道是哪几条、为什么，也就无从重试。
        flash.addFlashAttribute("batchFailures", result.failed());
        return "redirect:/admin/tickets";
    }

    /**
     * 解析 {@code 类型:id} 复合串，并把 AC2 的两条边界钉在服务端：
     * <b>跨类型整批拒绝</b>、<b>账号级处置只适用于用户举报工单</b>。
     *
     * <p>为什么跨类型不能批：不同类型工单的处置对象含义根本不同 ——
     * 内容举报处置的是<b>内容</b>，账号举报处置的是<b>人</b>。混在一批里执行同一个动作没有意义。
     */
    private static List<Long> parseAccountReportIds(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw AppException.validation("请先勾选要处理的工单");
        }
        String firstType = null;
        List<Long> ids = new java.util.ArrayList<>(tokens.size());
        for (String token : tokens) {
            int sep = token.indexOf(':');
            if (sep <= 0) {
                throw AppException.validation("工单标识格式不正确");
            }
            String type = token.substring(0, sep);
            if (firstType == null) {
                firstType = type;
            } else if (!firstType.equals(type)) {
                throw AppException.validation("不同类型的工单不能一起批量处理");
            }
            try {
                ids.add(Long.parseLong(token.substring(sep + 1)));
            } catch (NumberFormatException e) {
                throw AppException.validation("工单标识格式不正确");
            }
        }
        if (!TicketType.ACCOUNT_REPORT.name().equals(firstType)) {
            throw AppException.validation("警告 / 封号只适用于用户举报工单");
        }
        return ids;
    }

    private static boolean canSuspend() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null) {
            return false;
        }
        var authorities = auth.getAuthorities();
        boolean superAdmin = authorities.stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
        boolean deactivate = authorities.stream()
                .anyMatch(a -> "user.deactivate".equals(a.getAuthority()));
        return superAdmin || deactivate;
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

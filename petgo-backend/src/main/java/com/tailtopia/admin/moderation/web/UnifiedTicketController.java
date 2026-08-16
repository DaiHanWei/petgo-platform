package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import com.tailtopia.admin.moderation.service.UnifiedTicketQueryService;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.moderation.domain.AccountReportEntry;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

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

    private static final int PAGE_SIZE = 20;

    private final UnifiedTicketQueryService query;
    private final AccountReportEntryRepository reportEntries;
    private final AccountDisposalRepository disposals;
    private final AccountQueryService accountQueryService;

    public UnifiedTicketController(UnifiedTicketQueryService query,
            AccountReportEntryRepository reportEntries, AccountDisposalRepository disposals,
            AccountQueryService accountQueryService) {
        this.query = query;
        this.reportEntries = reportEntries;
        this.disposals = disposals;
        this.accountQueryService = accountQueryService;
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

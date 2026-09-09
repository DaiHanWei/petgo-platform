package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.moderation.dto.TicketDetailView;
import com.tailtopia.admin.moderation.dto.TicketDisposeResult;
import com.tailtopia.admin.moderation.dto.TicketFilters;
import com.tailtopia.admin.moderation.dto.TicketQueueRow;
import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import com.tailtopia.admin.moderation.web.UnifiedTicketController.ReportEntryView;
import com.tailtopia.admin.throttle.dto.ThrottleStatusRow;
import com.tailtopia.admin.throttle.service.AdminThrottleReadService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.dto.AdminContentRow;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.AccountDisposal;
import com.tailtopia.moderation.domain.AccountReport;
import com.tailtopia.moderation.domain.AccountReportEntry;
import com.tailtopia.moderation.repository.AccountDisposalRepository;
import com.tailtopia.moderation.repository.AccountReportEntryRepository;
import com.tailtopia.moderation.repository.AccountReportRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A2 被举报用户工作台的只读装配（V1.3.0 Story 2.5）：两态计数、左栏队列、右栏四区、处置后「下一条 + 计数」。
 * <b>不含任何写操作</b>——处置仍走 {@code AccountDisposalService} / {@code RankThrottleService} 既有端点。
 * 作用域只有 {@link TicketType#ACCOUNT_REPORT}（内容 / 名称 / 头像归 A1，两页同源不同类）。
 */
@Service
public class TicketsWorkbenchService {

    public static final int PAGE_SIZE = 20;
    static final int SAMPLE_SIZE = 6;
    static final Set<TicketType> SCOPE = EnumSet.of(TicketType.ACCOUNT_REPORT);

    private final UnifiedTicketQueryService tickets;
    private final AccountReportRepository reports;
    private final AccountReportEntryRepository entries;
    private final AccountDisposalRepository disposals;
    private final AccountQueryService accounts;
    private final ContentService content;
    private final AdminThrottleReadService throttleRead;
    private final AdminAccountRepository adminAccounts;

    public TicketsWorkbenchService(UnifiedTicketQueryService tickets, AccountReportRepository reports,
            AccountReportEntryRepository entries, AccountDisposalRepository disposals, AccountQueryService accounts,
            ContentService content, AdminThrottleReadService throttleRead, AdminAccountRepository adminAccounts) {
        this.tickets = tickets;
        this.reports = reports;
        this.entries = entries;
        this.disposals = disposals;
        this.accounts = accounts;
        this.content = content;
        this.throttleRead = throttleRead;
        this.adminAccounts = adminAccounts;
    }

    /** 左栏一页：排序沿用 CTE（分降序、同分最早优先）；已处置态补结果 / 操作人 / 时间；限流中标记整页一次取。 */
    @Transactional(readOnly = true)
    public Page<TicketQueueRow> queue(TicketFilters f) {
        Page<UnifiedTicketRow> page = tickets.search(SCOPE, TicketType.ACCOUNT_REPORT,
                f.handled() ? null : TicketStatusBucket.PENDING, f.q(), extraFor(f), PageRequest.of(f.page(), PAGE_SIZE));
        Map<Long, ThrottleStatusRow> throttles = throttleRead.forAccounts(page.getContent().stream()
                .map(UnifiedTicketRow::targetUserId).filter(Objects::nonNull).distinct().toList(), Instant.now());
        if (!f.handled()) {
            List<TicketQueueRow> rows = page.getContent().stream().map(r -> new TicketQueueRow(r, null, null, null,
                    r.targetUserId() != null && throttles.get(r.targetUserId()) != null)).toList();
            return new PageImpl<>(rows, page.getPageable(), page.getTotalElements());
        }
        // 已处置态：整页一次取工单 / 处置记录 / 操作人（不逐行查，UnifiedTicketQueryService 类注释口径）。
        List<Long> ids = page.getContent().stream().map(UnifiedTicketRow::sourceId).toList();
        Map<Long, AccountReport> reportById = new HashMap<>();
        reports.findAllById(ids).forEach(r -> reportById.put(r.getId(), r));
        Map<Long, AccountDisposal> lastByReport = new HashMap<>();
        disposals.findByReportIdInOrderByCreatedAtDesc(ids).forEach(d -> lastByReport.putIfAbsent(d.getReportId(), d));
        Map<Long, String> operators = operatorNames(reportById.values().stream().map(AccountReport::getHandledBy).toList());
        List<TicketQueueRow> rows = page.getContent().stream().map(r -> {
            boolean throttled = r.targetUserId() != null && throttles.get(r.targetUserId()) != null;
            AccountReport report = reportById.get(r.sourceId());
            String result = r.status().name();
            Long by = null;
            Instant at = null;
            if (report != null) {
                result = report.getStatus().name();
                by = report.getHandledBy();
                at = report.getHandledAt();
                AccountDisposal last = lastByReport.get(r.sourceId());
                if (last != null && last.getDisposalType() != null) {
                    result = last.getDisposalType().name();
                }
            }
            String byName = by == null ? null : operators.getOrDefault(by, "#" + by);
            return new TicketQueueRow(r, result, byName, at, throttled);
        }).toList();
        return new PageImpl<>(rows, page.getPageable(), page.getTotalElements());
    }

    /** 两态计数（受举报类型 / 关键词筛选影响）：pending / handled。 */
    @Transactional(readOnly = true)
    public Map<String, Long> counts(TicketFilters f) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("pending", tickets.search(SCOPE, TicketType.ACCOUNT_REPORT, TicketStatusBucket.PENDING, f.q(),
                extraFor(new TicketFilters(false, f.reason(), f.q(), 0)), PageRequest.of(0, 1)).getTotalElements());
        out.put("handled", tickets.search(SCOPE, TicketType.ACCOUNT_REPORT, null, f.q(),
                extraFor(new TicketFilters(true, f.reason(), f.q(), 0)), PageRequest.of(0, 1)).getTotalElements());
        return out;
    }

    /** 当前筛选下待处置第一条（处置提交后 = 下一条）；无则空串。 */
    @Transactional(readOnly = true)
    public String nextId(TicketFilters f) {
        Page<UnifiedTicketRow> page = tickets.search(SCOPE, TicketType.ACCOUNT_REPORT, TicketStatusBucket.PENDING,
                f.q(), extraFor(f.pendingFirstPage()), PageRequest.of(0, 1));
        return page.isEmpty() ? "" : String.valueOf(page.getContent().get(0).sourceId());
    }

    /** 处置成功后：从 {@code HX-Current-URL} 还原筛选，算下一条 + 两态计数。 */
    @Transactional(readOnly = true)
    public TicketDisposeResult afterDispose(String currentUrl, long removedReportId) {
        TicketFilters f = TicketFilters.fromUrl(currentUrl).pendingFirstPage();
        Map<String, Long> counts = counts(f);
        return new TicketDisposeResult(nextId(f), removedReportId, counts.get("pending"), counts.get("handled"));
    }

    /** 右栏四区（AC3）。 */
    @Transactional(readOnly = true)
    public TicketDetailView detail(long reportId) {
        UnifiedTicketRow row = tickets.search(SCOPE, TicketType.ACCOUNT_REPORT, null, null,
                        new UnifiedTicketQueryService.Extra(null, null, false, reportId), PageRequest.of(0, 1))
                .getContent().stream().findFirst()
                .orElseThrow(() -> AppException.notFound("工单不存在").code("admin.err.ticket.notFound"));
        Long userId = row.targetUserId();
        User user = userId == null ? null : accounts.findUserById(userId).orElse(null);

        List<AccountReportEntry> entryRows = entries.findByReportIdOrderByCreatedAtDesc(reportId);
        Map<Long, String> nick = nicknamesOf(entryRows.stream().map(AccountReportEntry::getReporterId).toList());
        List<ReportEntryView> entryViews = entryRows.stream()
                .map(e -> new ReportEntryView(e.getReporterId(), nick.get(e.getReporterId()),
                        e.getReason() == null ? null : e.getReason().name(), e.getCreatedAt(), e.getDetail()))
                .toList();
        Map<Long, Long> perReporter = entryRows.stream().filter(e -> e.getReporterId() != null)
                .collect(Collectors.groupingBy(AccountReportEntry::getReporterId, Collectors.counting()));
        Set<Long> frequent = perReporter.entrySet().stream()
                .filter(en -> en.getValue() >= UnifiedTicketQueryService.FREQUENT_REPORTER_THRESHOLD)
                .map(Map.Entry::getKey).collect(Collectors.toSet());

        List<TicketDetailView.DisposalLine> lines = List.of();
        long disposalCount = 0;
        List<AdminContentRow> samples = List.of();
        ThrottleStatusRow throttle = null;
        if (userId != null) {
            List<AccountDisposal> ds = disposals.findByTargetUserIdOrderByCreatedAtDesc(userId);
            disposalCount = ds.size();
            Map<Long, String> operators = operatorNames(ds.stream().map(AccountDisposal::getOperatorId).toList());
            lines = ds.stream().map(d -> new TicketDetailView.DisposalLine(
                    d.getDisposalType() == null ? null : d.getDisposalType().name(),
                    d.getOperatorId() == null ? null : operators.getOrDefault(d.getOperatorId(), "#" + d.getOperatorId()),
                    d.getCreatedAt(), d.getReportId())).toList();
            samples = content.adminSearch(null, userId, null, null, null, null, null, SAMPLE_SIZE, 0);
            throttle = throttleRead.forAccounts(List.of(userId), Instant.now()).get(userId);
        }
        // 个性签名：仿冒 / 骚扰举报时签名往往就是证据本身（旧面板口径保留）。
        String signature = userId == null ? null : accounts.activeSignatureOf(userId).orElse(null);
        return new TicketDetailView(reportId, row, user == null ? null : user.getAvatarUrl(),
                user == null ? null : user.getCreatedAt(), signature, disposalCount, lines, entryViews, frequent, samples,
                throttle);
    }

    /** 处置端点按 reportId 收口；封号 / 警告表单要 targetUserId，从工单读（表单字段可被篡改，服务端另有校验）。 */
    @Transactional(readOnly = true)
    public Long targetUserIdOf(long reportId) {
        return reports.findById(reportId).map(AccountReport::getTargetUserId).orElse(null);
    }

    private static UnifiedTicketQueryService.Extra extraFor(TicketFilters f) {
        return new UnifiedTicketQueryService.Extra(null, null, f.handled(), null, f.reason());
    }

    private Map<Long, String> operatorNames(List<Long> ids) {
        Set<Long> distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        adminAccounts.findAllById(distinct).forEach(a -> out.put(a.getId(), a.getDisplayName()));
        return out;
    }

    private Map<Long, String> nicknamesOf(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        accounts.findAuthorViews(new HashSet<>(distinct)).forEach((id, view) -> {
            if (view != null && !view.deleted() && view.nickname() != null) {
                out.put(id, view.nickname());
            }
        });
        return out;
    }
}

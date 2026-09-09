package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.moderation.domain.ManualReviewItem;
import com.tailtopia.admin.moderation.domain.ReviewContentType;
import com.tailtopia.admin.moderation.dto.ReviewDetailView;
import com.tailtopia.admin.moderation.dto.ReviewDisposeResult;
import com.tailtopia.admin.moderation.dto.ReviewFilters;
import com.tailtopia.admin.moderation.dto.ReviewQueueRow;
import com.tailtopia.admin.moderation.dto.ReviewTab;
import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.moderation.web.UnifiedTicketController.ReportEntryView;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.avatarmoderation.domain.AvatarReview;
import com.tailtopia.avatarmoderation.repository.AvatarReviewRepository;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.domain.ContentReport;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.namemoderation.domain.NameModerationRecord;
import com.tailtopia.namemoderation.repository.NameModerationRecordRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A1 统一复核工作台的只读装配（V1.3.0 Story 2.4）：页签计数、左栏队列（待处理 / 已处理两态）、右栏三卡、
 * 处置后的「下一条 + 计数」。<b>不含任何写操作</b>——处置仍走各自既有端点与 service（AC4 红线）。
 * 全部基于 {@link UnifiedTicketQueryService} 的联合 CTE（排序 / overdue 口径不变）+ 各源表按 id 直读。
 */
@Service
public class ManualReviewWorkbenchService {

    public static final int PAGE_SIZE = 20;
    static final Set<com.tailtopia.admin.moderation.dto.TicketType> SCOPE = java.util.EnumSet.of(
            com.tailtopia.admin.moderation.dto.TicketType.CONTENT_REPORT,
            com.tailtopia.admin.moderation.dto.TicketType.ACCOUNT_IDENTITY,
            com.tailtopia.admin.moderation.dto.TicketType.CONTENT_SUBMISSION);

    private final UnifiedTicketQueryService tickets;
    private final ManualReviewItemRepository items;
    private final NameModerationRecordRepository names;
    private final AvatarReviewRepository avatars;
    private final ReportService reports;
    private final ContentReportRepository contentReports;
    private final ContentService content;
    private final CommentRepository comments;
    private final UserRepository users;
    private final PetProfileRepository pets;
    private final AdminAccountRepository adminAccounts;

    public ManualReviewWorkbenchService(UnifiedTicketQueryService tickets, ManualReviewItemRepository items,
            NameModerationRecordRepository names, AvatarReviewRepository avatars, ReportService reports,
            ContentReportRepository contentReports, ContentService content, CommentRepository comments,
            UserRepository users, PetProfileRepository pets, AdminAccountRepository adminAccounts) {
        this.tickets = tickets;
        this.items = items;
        this.names = names;
        this.avatars = avatars;
        this.reports = reports;
        this.contentReports = contentReports;
        this.content = content;
        this.comments = comments;
        this.users = users;
        this.pets = pets;
        this.adminAccounts = adminAccounts;
    }

    /** 左栏一页（按页签 / 两态 / 筛选；排序沿用 CTE）。已处理态补结果 / 操作人 / 时间 / 理由。 */
    @Transactional(readOnly = true)
    public Page<ReviewQueueRow> queue(ReviewFilters f) {
        Page<UnifiedTicketRow> page = tickets.search(SCOPE, f.tab().type(),
                f.handled() ? null : TicketStatusBucket.PENDING, f.q(), extraFor(f),
                PageRequest.of(f.page(), PAGE_SIZE));
        List<ReviewQueueRow> rows = page.getContent().stream().map(r -> f.handled() ? handled(f.tab(), r)
                : new ReviewQueueRow(f.tab(), r, null, null, null, null)).toList();
        return new PageImpl<>(rows, page.getPageable(), page.getTotalElements());
    }

    /** 各页签待处理数（不受当前页签筛选影响，只受关键词影响）。 */
    @Transactional(readOnly = true)
    public Map<ReviewTab, Long> counts(ReviewFilters f) {
        Map<ReviewTab, Long> out = new EnumMap<>(ReviewTab.class);
        for (ReviewTab t : ReviewTab.values()) {
            out.put(t, tickets.search(SCOPE, t.type(), TicketStatusBucket.PENDING, f.q(),
                    new UnifiedTicketQueryService.Extra(t.subTypes(), null, false), PageRequest.of(0, 1))
                    .getTotalElements());
        }
        return out;
    }

    /** 当前页签筛选下的第一条待处理（处置提交后 = 下一条）；无则空串。 */
    @Transactional(readOnly = true)
    public String nextId(ReviewFilters f) {
        Page<UnifiedTicketRow> page = tickets.search(SCOPE, f.tab().type(), TicketStatusBucket.PENDING, f.q(),
                extraFor(f), PageRequest.of(0, 1));
        return page.isEmpty() ? "" : String.valueOf(page.getContent().get(0).sourceId());
    }

    /** 处置成功后：从 {@code HX-Current-URL} 还原筛选（页签以被处置行为准），算下一条 + 各页签计数。 */
    @Transactional(readOnly = true)
    public ReviewDisposeResult afterDispose(String currentUrl, ReviewTab tab, long removedSourceId) {
        ReviewFilters f = ReviewFilters.fromUrl(currentUrl).withTab(tab);
        f = new ReviewFilters(tab, false, f.subType(), f.priority(), f.category(), f.q(), 0);
        Map<ReviewTab, Long> counts = counts(f);
        long other = counts.entrySet().stream().filter(e -> e.getKey() != tab).mapToLong(Map.Entry::getValue).sum();
        // 内容举报按帖聚合：单条驳回只关一条举报单，该帖仍有其他 PENDING 单时行不能删（复审 #4）——
        // 重渲染该行（actionRef 已换成下一条待处理单）并让右栏重新拉它。
        if (tab == ReviewTab.REPORT && !reports.findPendingForPost(removedSourceId).isEmpty()) {
            UnifiedTicketRow row = tickets.search(SCOPE, tab.type(), null, null,
                            new UnifiedTicketQueryService.Extra(null, null, false, removedSourceId), PageRequest.of(0, 1))
                    .getContent().stream().findFirst().orElse(null);
            if (row != null && row.status() == TicketStatusBucket.PENDING) {
                return new ReviewDisposeResult(tab, String.valueOf(removedSourceId), removedSourceId, counts, other,
                        new ReviewQueueRow(tab, row, null, null, null, null));
            }
        }
        return new ReviewDisposeResult(tab, nextId(f), removedSourceId, counts, other);
    }

    /** 内容举报单 id → 所属帖 id（下架 / 驳回端点按举报单收口，行按帖聚合）。 */
    @Transactional(readOnly = true)
    public Optional<Long> postIdOfReport(long reportId) {
        return contentReports.findById(reportId).map(ContentReport::getPostId);
    }

    /** 右栏三卡（AC3）。 */
    @Transactional(readOnly = true)
    public ReviewDetailView detail(ReviewTab tab, long sourceId) {
        UnifiedTicketRow row = tickets.search(SCOPE, tab.type(), null, null,
                        new UnifiedTicketQueryService.Extra(tab.subTypes(), null, false, sourceId), PageRequest.of(0, 1))
                .getContent().stream().findFirst()
                .orElseThrow(() -> AppException.notFound("复核单不存在").code("admin.err.review.notFound"));
        ContentService.AdminPostDetail post = null;
        String commentBody = null;
        boolean contentDeleted = false;
        List<ReportEntryView> entries = List.of();
        String priority = null;
        String submittedValue = null;
        String currentValue = null;
        String submittedAvatar = null;
        String currentAvatar = null;
        String machineReason = null;
        long pendingEntries = 0;
        switch (tab) {
            case SUBMISSION -> {
                ManualReviewItem item = items.findById(sourceId).orElse(null);
                if (item != null) {
                    priority = item.getPriority() == null ? null : item.getPriority().name();
                    if (item.getContentType() == ReviewContentType.COMMENT) {
                        Comment c = comments.findById(item.getContentId()).orElse(null);
                        commentBody = c == null ? null : c.getBody();
                        contentDeleted = c == null;
                        if (c != null) {
                            post = content.adminDetail(c.getPostId()).orElse(null);
                        }
                    } else {
                        post = content.adminDetail(item.getContentId()).orElse(null);
                        contentDeleted = post == null || post.deleted();
                    }
                    machineReason = "MACHINE_UNSURE"; // 机审「拿不准」→ 送人工（队列表不存命中原因，仅标记）
                }
            }
            case REPORT -> {
                post = content.adminDetail(sourceId).orElse(null);
                contentDeleted = post == null || post.deleted();
                List<ContentReport> list = reports.findAllForPost(sourceId);
                pendingEntries = list.stream().filter(r -> r.getStatus() == com.tailtopia.moderation.domain.ReportStatus.PENDING).count();
                Map<Long, String> nick = nicknamesOf(list.stream().map(ContentReport::getReporterId).toList());
                entries = list.stream().map(r -> new ReportEntryView(r.getReporterId(), nick.get(r.getReporterId()),
                        r.getReasonType() == null ? null : r.getReasonType().name(), r.getCreatedAt(), null)).toList();
            }
            case NAME -> {
                NameModerationRecord rec = names.findById(sourceId).orElse(null);
                if (rec != null) {
                    submittedValue = rec.getSubmittedValue();
                    if ("NICKNAME".equals(rec.getTargetType().name())) {
                        currentValue = users.findById(rec.getTargetRefId()).map(User::getNickname).orElse(null);
                    } else {
                        currentValue = pets.findById(rec.getTargetRefId()).map(PetProfile::getName).orElse(null);
                    }
                }
            }
            case AVATAR -> {
                AvatarReview rev = avatars.findById(sourceId).orElse(null);
                if (rev != null) {
                    submittedAvatar = rev.getAvatarUrl();
                    if ("USER_AVATAR".equals(rev.getSubjectType().name())) {
                        currentAvatar = users.findById(rev.getSubjectId()).map(User::getAvatarUrl).orElse(null);
                    } else {
                        currentAvatar = pets.findById(rev.getSubjectId()).map(PetProfile::getAvatarUrl).orElse(null);
                    }
                }
            }
            default -> { }
        }
        return new ReviewDetailView(tab, sourceId, row.subType(), row.status(), row.targetUserId(), row.targetNickname(),
                row.targetDeleted(), row.disposalCount(), row.earliestAt(), priority, row.overdue(), post, commentBody,
                row.contentRefId(), contentDeleted, entries, pendingEntries, row.actionRef(), submittedValue, currentValue,
                submittedAvatar, currentAvatar, machineReason);
    }

    private static UnifiedTicketQueryService.Extra extraFor(ReviewFilters f) {
        Set<String> subTypes = f.tab().subTypes();
        if (f.tab().hasSubTypeFilter() && f.subType() != null) {
            Set<String> narrowed = new HashSet<>();
            for (String s : subTypes) {
                if (("user".equalsIgnoreCase(f.subType()) && (s.equals("NICKNAME") || s.equals("USER_AVATAR")))
                        || ("pet".equalsIgnoreCase(f.subType()) && (s.equals("PET_NAME") || s.equals("PET_AVATAR")))
                        || s.equalsIgnoreCase(f.subType())) {
                    narrowed.add(s);
                }
            }
            if (!narrowed.isEmpty()) {
                subTypes = narrowed;
            }
        }
        String like = null;
        if (f.tab() == ReviewTab.REPORT && f.category() != null) {
            like = f.category();
        } else if (f.tab() == ReviewTab.SUBMISSION && f.priority() != null) {
            like = f.priority().toUpperCase() + " ·";
        }
        return new UnifiedTicketQueryService.Extra(subTypes.isEmpty() ? null : subTypes, like, f.handled());
    }

    private ReviewQueueRow handled(ReviewTab tab, UnifiedTicketRow r) {
        String result = r.status().name();
        Long by = null;
        java.time.Instant at = null;
        String reason = null;
        switch (tab) {
            case SUBMISSION -> {
                ManualReviewItem item = items.findById(r.sourceId()).orElse(null);
                if (item != null) {
                    result = item.getStatus().name();
                    by = item.getDecidedBy();
                    at = item.getDecidedAt();
                }
            }
            case REPORT -> {
                ContentReport cr = reports.findAllForPost(r.sourceId()).stream()
                        .filter(x -> x.getHandledAt() != null).findFirst().orElse(null);
                if (cr != null) {
                    result = cr.getStatus().name();
                    by = cr.getHandledBy();
                    at = cr.getHandledAt();
                }
            }
            case NAME -> {
                NameModerationRecord rec = names.findById(r.sourceId()).orElse(null);
                if (rec != null) {
                    result = rec.getStatus().name().replace("RESOLVED_", "");
                    by = rec.getDecidedBy();
                    at = rec.getDecidedAt();
                    reason = rec.getDecisionReason();
                }
            }
            case AVATAR -> {
                AvatarReview rev = avatars.findById(r.sourceId()).orElse(null);
                if (rev != null) {
                    result = rev.getVerdict() == null ? rev.getStatus().name() : rev.getVerdict().name();
                    at = rev.getUpdatedAt();
                }
            }
            default -> { }
        }
        String byName = by == null ? null : adminAccounts.findById(by).map(AdminAccount::getDisplayName).orElse("#" + by);
        return new ReviewQueueRow(tab, r, result, byName, at, reason);
    }

    private Map<Long, String> nicknamesOf(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new LinkedHashMap<>();
        users.findAllById(new HashSet<>(ids).stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .forEach(u -> out.put(u.getId(), u.getNickname()));
        return out;
    }
}

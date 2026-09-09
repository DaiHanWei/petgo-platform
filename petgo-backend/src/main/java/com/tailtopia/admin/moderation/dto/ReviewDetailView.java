package com.tailtopia.admin.moderation.dto;

import com.tailtopia.admin.moderation.web.UnifiedTicketController.ReportEntryView;
import com.tailtopia.admin.places.dto.PlaceDrawerView;
import com.tailtopia.content.service.ContentService;
import java.time.Instant;
import java.util.List;

/**
 * 工作台右栏三卡的数据（V1.3.0 Story 2.4 AC3）。按页签取用：
 * <ul>
 *   <li>送审 / 举报：{@code post}（帖全文 + 图片 + 作者）或 {@code commentBody}（评论送审）；举报另有 {@code entries}
 *       （全部举报单）与 {@code pendingEntries}（其中待处理条数，整帖一键驳回的确认文案用）；</li>
 *   <li>名称：{@code submittedValue}（待审名，大字号）+ {@code currentValue}（当前生效值）；</li>
 *   <li>头像：{@code submittedAvatarUrl} + {@code currentAvatarUrl}。</li>
 * </ul>
 * {@code targetDeleted} → 只读作废态；{@code contentDeleted} → 快照卡「内容已删除」占位，下架钮禁用。
 */
public record ReviewDetailView(
        ReviewTab tab,
        long sourceId,
        String subType,
        TicketStatusBucket status,
        Long targetUserId,
        String targetNickname,
        boolean targetDeleted,
        long disposalCount,
        Instant earliestAt,
        String priority,
        boolean overdue,
        ContentService.AdminPostDetail post,
        String commentBody,
        Long contentRefId,
        boolean contentDeleted,
        List<ReportEntryView> entries,
        long pendingEntries,
        Long actionRef,
        String submittedValue,
        String currentValue,
        String submittedAvatarUrl,
        String currentAvatarUrl,
        String machineReason,
        /** 场所举报页签（V1.3.0 Story 5.4）：场所快照（复用 B6 抽屉视图）；场所已软删 → null（contentDeleted=true）。 */
        PlaceDrawerView place) {

    /** 旧 22 参构造（非场所页签）。 */
    public ReviewDetailView(ReviewTab tab, long sourceId, String subType, TicketStatusBucket status, Long targetUserId,
            String targetNickname, boolean targetDeleted, long disposalCount, Instant earliestAt, String priority, boolean overdue,
            ContentService.AdminPostDetail post, String commentBody, Long contentRefId, boolean contentDeleted,
            List<ReportEntryView> entries, long pendingEntries, Long actionRef, String submittedValue, String currentValue,
            String submittedAvatarUrl, String currentAvatarUrl, String machineReason) {
        this(tab, sourceId, subType, status, targetUserId, targetNickname, targetDeleted, disposalCount, earliestAt, priority, overdue,
                post, commentBody, contentRefId, contentDeleted, entries, pendingEntries, actionRef, submittedValue, currentValue,
                submittedAvatarUrl, currentAvatarUrl, machineReason, null);
    }

    public boolean pending() {
        return status == TicketStatusBucket.PENDING;
    }
}

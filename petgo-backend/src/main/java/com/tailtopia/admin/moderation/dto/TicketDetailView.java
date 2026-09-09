package com.tailtopia.admin.moderation.dto;

import com.tailtopia.admin.moderation.web.UnifiedTicketController.ReportEntryView;
import com.tailtopia.admin.throttle.dto.ThrottleStatusRow;
import com.tailtopia.content.dto.AdminContentRow;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A2 右栏四区的数据（V1.3.0 Story 2.5 AC3）：① 账号卡（头像 / 昵称 / id / 注册时间 / 历史处置）② 举报明细（高频举报人打标）
 * ③ 近期内容抽样 ④ 操作区（模板按 {@code pending} / {@code targetDeleted} / 权限渲染）。
 *
 * @param frequentReporterIds 对该账号累计举报 ≥ 阈值的举报人（{@code frequentCount} 同口径）
 * @param samples             该账号最近内容（含已下架，运营视角）
 * @param throttle            当前生效的账号级限流（无则 null）
 */
public record TicketDetailView(
        long reportId,
        UnifiedTicketRow row,
        String avatarUrl,
        Instant registeredAt,
        String signature,
        long disposalCount,
        List<DisposalLine> disposals,
        List<ReportEntryView> entries,
        Set<Long> frequentReporterIds,
        List<AdminContentRow> samples,
        ThrottleStatusRow throttle) {

    /** 处置历史一行：类型 / 操作人显示名 / 时间 / 关联工单。 */
    public record DisposalLine(String type, String operator, Instant at, Long reportId) {
    }

    public boolean pending() {
        return row.status() == TicketStatusBucket.PENDING;
    }

    public boolean targetDeleted() {
        return row.targetDeleted();
    }

    public Long targetUserId() {
        return row.targetUserId();
    }

    public String nickname() {
        return row.targetNickname();
    }

    public boolean isFrequent(Long reporterId) {
        return reporterId != null && frequentReporterIds.contains(reporterId);
    }
}

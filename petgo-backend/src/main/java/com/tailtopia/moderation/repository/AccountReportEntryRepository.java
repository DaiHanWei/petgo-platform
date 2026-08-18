package com.tailtopia.moderation.repository;

import com.tailtopia.moderation.domain.AccountReportEntry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号举报明细（Story 2.1）。<b>只追加，不更新、不删除</b>。
 *
 * <p>Epic 3 的优先级公式要按 {@code (report_id, reporter_id)} 聚合出「多少人报过 / 一共多少次 /
 * 其中几人报了≥5 次」，索引 {@code idx_account_report_entries_report_reporter} 就是为它建的。
 */
public interface AccountReportEntryRepository extends JpaRepository<AccountReportEntry, Long> {

    /** 某工单的全部明细，时间倒序（后台工单详情用）。 */
    List<AccountReportEntry> findByReportIdOrderByCreatedAtDesc(long reportId);

    /**
     * 该举报人在<b>指定时刻之后</b>是否已对这条工单提交过 —— 秒级去重（AC11）。
     *
     * <p>防的是双击穿透与提交中的网络重试，<b>不是频率限制</b>：超出窗口的再次举报照常追加一行。
     * 不做这道去重的后果不是数据错误，而是污染两个直接给运营看的数字：
     * 工单上的「12 人 / 27 次」，以及「同一人举报 ≥5 次」的高频加成判定。
     */
    boolean existsByReportIdAndReporterIdAndCreatedAtAfter(long reportId, long reporterId,
            Instant after);

    long countByReportId(long reportId);

    /** 某工单的全部<b>去重</b>举报人（处置回告 FR-51：每个举报人一条模糊通知，不按明细重复发）。 */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT e.reporterId FROM AccountReportEntry e WHERE e.reportId = :reportId")
    List<Long> findDistinctReporterIds(
            @org.springframework.data.repository.query.Param("reportId") long reportId);
}

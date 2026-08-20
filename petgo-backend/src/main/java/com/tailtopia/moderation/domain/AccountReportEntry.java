package com.tailtopia.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 账号举报明细（Story 2.1，FR-58）——<b>每一次举报都是一行，永远追加、绝不覆盖</b>。
 *
 * <p>甲第一次报「持续骚扰」、第二次报「仿冒他人」并写了补充说明，那就是<b>两行</b>：
 * 两次的类型都在，第二次的说明追加保存。运营在工单里看到的是这个人被举报的全过程，
 * 而不是一个被反复覆盖的「最新理由」。
 *
 * <p>⚠️ <b>本表没有任何唯一约束，这是刻意的</b>。既有内容举报 {@code content_reports} 唯一键是
 * {@code (reporter_id, post_id)}、service 里两处裸 {@code return} 把重复举报幂等吞掉 ——
 * <b>照抄那套做法会让次数、类型变化、补充说明全部丢失</b>。
 * service 层唯一允许「吞掉」的是<b>秒级</b>去重（防双击穿透与网络重试），不是限制用户意图。
 *
 * <p>⚠️ {@link #detail} 是用户自由文本，<b>禁止进入任何日志</b>（日志禁 PII 红线）。
 */
@Entity
@Table(name = "account_report_entries")
public class AccountReportEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 24)
    private AccountReportReason reason;

    /** 「其他」的补充说明（≤200 字）；其余四类恒为 null。⚠️ 用户自由文本，禁止进日志。 */
    @Column(name = "detail", length = 200)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountReportEntry() {
    }

    public static AccountReportEntry create(long reportId, long reporterId,
            AccountReportReason reason, String detail) {
        AccountReportEntry e = new AccountReportEntry();
        e.reportId = reportId;
        e.reporterId = reporterId;
        e.reason = reason;
        e.detail = detail;
        return e;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getReportId() {
        return reportId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public AccountReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

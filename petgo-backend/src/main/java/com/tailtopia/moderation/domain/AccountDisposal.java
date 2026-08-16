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
 * 账号级处置记录（Story 3.1 建表，**写入由 Story 3.2 接入**）。一行 = 对某账号执行过的一次警告或封号。
 *
 * <p>在此之前账号级处置<b>没有任何结构化留痕</b>（只有审计日志里的一行中文 detail），
 * 运营想知道「这个人以前被警告过几次」只能靠人肉翻。工单列表的「历史处置次数」读的就是本表。
 *
 * <p>{@code operatorId} / {@code reportId} 都可空：处置不一定由某条工单触发（也可能是运营主动巡查）。
 */
@Entity
@Table(name = "account_disposals")
public class AccountDisposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposal_type", nullable = false, length = 16)
    private AccountDisposalType disposalType;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountDisposal() {
    }

    public static AccountDisposal create(long targetUserId, AccountDisposalType type,
            Long operatorId, Long reportId) {
        AccountDisposal d = new AccountDisposal();
        d.targetUserId = targetUserId;
        d.disposalType = type;
        d.operatorId = operatorId;
        d.reportId = reportId;
        return d;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public AccountDisposalType getDisposalType() {
        return disposalType;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public Long getReportId() {
        return reportId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

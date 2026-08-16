package com.tailtopia.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 账号举报工单（Story 2.1，FR-58 · AD-2）。<b>一行 = 一个被举报的账号</b>，{@code target_user_id} 唯一。
 *
 * <p>同一个人被 12 个用户举报 27 次，也只有<b>这一条</b>工单；那 27 次分别落在
 * {@link AccountReportEntry} 里。「同一举报人对同一账号只占一条工单」这条要求由本粒度天然满足。
 *
 * <p>⚠️ {@code firstReportedAt} 只在建单时写一次，<b>工单翻回待处置时不刷新</b> ——
 * 它回答的是「这个人第一次被人举报是什么时候」，刷新了运营就看不出一个账号被盯了多久。
 */
@Entity
@Table(name = "account_reports")
public class AccountReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountReportStatus status = AccountReportStatus.PENDING;

    @Column(name = "first_reported_at", nullable = false, updatable = false)
    private Instant firstReportedAt;

    @Column(name = "handled_by")
    private Long handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountReport() {
    }

    public static AccountReport create(long targetUserId) {
        AccountReport r = new AccountReport();
        r.targetUserId = targetUserId;
        r.status = AccountReportStatus.PENDING;
        r.firstReportedAt = Instant.now();
        return r;
    }

    /**
     * 已处置的工单又被举报 → <b>翻回待处置，不新建工单</b>（AC9）。
     *
     * <p>历史处置留痕在 Epic 3 的处置记录表里，<b>不因翻回而丢失</b>；这里只清掉「谁在何时处置的」
     * 这两个「当前处置」字段，让工单重新回到队列。已经是 PENDING 时是 no-op。
     *
     * @return 是否真的发生了翻回（供调用方判断要不要写日志/统计，非必需）
     */
    public boolean reopenIfHandled() {
        if (status == AccountReportStatus.PENDING) {
            return false;
        }
        this.status = AccountReportStatus.PENDING;
        this.handledBy = null;
        this.handledAt = null;
        return true;
    }

    /**
     * 运营处置完成 / 判为无需处置（Story 3.2 AC7）：记下状态、处理人与处理时刻。
     *
     * <p>⚠️ 之后该账号<b>又被举报</b>时，本工单会经 {@link #reopenIfHandled()} 翻回待处置 ——
     * 那只清「当前处置」这两个字段，<b>account_disposals 里已经写下的历史处置记录一条都不减</b>。
     */
    public void handleBy(long adminAccountId, AccountReportStatus decision) {
        this.status = decision;
        this.handledBy = adminAccountId;
        this.handledAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.firstReportedAt == null) {
            this.firstReportedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public AccountReportStatus getStatus() {
        return status;
    }

    public Instant getFirstReportedAt() {
        return firstReportedAt;
    }

    public Long getHandledBy() {
        return handledBy;
    }

    public Instant getHandledAt() {
        return handledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

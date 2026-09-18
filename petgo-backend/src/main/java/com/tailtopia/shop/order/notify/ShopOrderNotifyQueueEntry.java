package com.tailtopia.shop.order.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 新订单 Lark 提醒的待发行（Story 3-4 / SHOP-FR-24）。
 *
 * <p>🔴 <b>不是通知中心的一条</b>：不发用户、不进 {@code notifications}、无 App 推送。
 * 收件人是运营的 Lark 群，这是给他们的**运维信号**。
 */
@Entity
@Table(name = "shop_order_notify_queue")
public class ShopOrderNotifyQueueEntry {

    public enum Status {
        PENDING, SENT, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code shop_orders.id}。唯一约束即幂等键（支付回调双通道会重复投递）。 */
    @Column(name = "shop_order_id", nullable = false, updatable = false)
    private Long shopOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** 🔴 列是 INTEGER，字段必须是 int —— SMALLINT 配 int 会让 ddl-auto=validate 全红（仓内踩过）。 */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopOrderNotifyQueueEntry() {
    }

    public static ShopOrderNotifyQueueEntry pending(long shopOrderId) {
        ShopOrderNotifyQueueEntry e = new ShopOrderNotifyQueueEntry();
        e.shopOrderId = shopOrderId;
        e.status = Status.PENDING;
        e.retryCount = 0;
        return e;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    /**
     * 投递失败。超过 {@code maxRetries} 转 {@link Status#FAILED} 不再重试 ——
     * 一条发不出去的运维提醒不值得永远占着队列，也不值得每轮都去撞一次同一个墙。
     *
     * @return 是否已放弃（转 FAILED）
     */
    public boolean markFailedAttempt(int maxRetries) {
        this.retryCount++;
        if (this.retryCount >= maxRetries) {
            this.status = Status.FAILED;
            return true;
        }
        return false; // 保持 PENDING，下一轮再试
    }

    /**
     * 冷却后把 {@link Status#FAILED} 放回队列重试（v1.3.0 shop-v2 复审 #15）。
     *
     * <p>🔴 原实现里 FAILED 是<b>不可恢复的终态</b>：{@code collectWindow} 只查 PENDING，
     * 全仓又没有任何重入队路径。于是「Lark 挂了十几分钟」或「receive_id 填错」这种
     * 完全可恢复的故障，会把那段时间里的整批订单永久判死 —— 事后把配置改对也救不回来，
     * 而这条提醒正是通知仓库发货的唯一信号。
     *
     * <p>重试次数一并清零：它计的是「这一轮故障里试了几次」，不是这行的终身账。
     */
    public void requeue() {
        this.status = Status.PENDING;
        this.retryCount = 0;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getShopOrderId() {
        return shopOrderId;
    }

    public Status getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

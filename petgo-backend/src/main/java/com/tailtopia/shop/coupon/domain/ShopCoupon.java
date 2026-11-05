package com.tailtopia.shop.coupon.domain;

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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 券模板（v1.4.2 shop Story 2.1，{@code shop_coupons}，D-1）。
 *
 * <p>对外只暴露 {@link #publicToken}（{@code ShopTokenGenerator}），自增 id 不外露。
 *
 * <p>🔴 {@code issued_count} 只经 {@code ShopCouponRepository.incrementIssuedIfAvailable} 的条件更新改
 * （影响 0 行 = 领完），实体上 {@code insertable/updatable = false}：
 * 否则先读后停券的 save 会把读到的旧发放数写回，吞掉并发领取的计数。
 *
 * <p>停券只经 {@code ShopCouponRepository.stopIfActive} 条件更新（并发停券只有一个生效，复审修复），实体不提供停用方法。
 *
 * <p>发放期（{@code issue_*}）只决定能不能领 / 兑 / 发；使用有效期（{@code valid_*}）只决定能不能用（FR-130.6）。
 */
@Entity
@Table(name = "shop_coupons")
@DynamicUpdate
public class ShopCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private CouponKind kind;

    @Column(name = "name_id", nullable = false, length = 60)
    private String nameId;

    @Column(name = "name_en", nullable = false, length = 60)
    private String nameEn;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "min_spend", nullable = false)
    private long minSpend;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    private CouponScopeType scopeType;

    /** 品类码或商品 public_token 数组（照 {@code ShopProduct.feedingGuide} 的 JSONB 映射）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_values", nullable = false)
    private List<String> scopeValues = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "acquire_type", nullable = false, length = 16, updatable = false)
    private CouponAcquireType acquireType;

    @Column(name = "issue_start_at", nullable = false)
    private Instant issueStartAt;

    @Column(name = "issue_end_at", nullable = false)
    private Instant issueEndAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "valid_mode", nullable = false, length = 16)
    private CouponValidMode validMode;

    @Column(name = "valid_start_at")
    private Instant validStartAt;

    @Column(name = "valid_end_at")
    private Instant validEndAt;

    @Column(name = "valid_days")
    private Integer validDays;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "issued_count", nullable = false, insertable = false, updatable = false)
    private int issuedCount;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CouponStatus status = CouponStatus.ACTIVE;

    /** 停用时是否选了「作废已领未用」（D-3：其后取消的订单退券时转 VOIDED 不退回）。 */
    @Column(name = "void_on_stop", nullable = false)
    private boolean voidOnStop;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShopCoupon() {
    }

    /** 由已通过 {@code CouponAdminService} 校验的字段构造（ACTIVE）。 */
    @SuppressWarnings("java:S107")
    public static ShopCoupon create(String publicToken, CouponKind kind, String nameId, String nameEn,
            long amount, long minSpend, CouponScopeType scopeType, List<String> scopeValues,
            CouponAcquireType acquireType, Instant issueStartAt, Instant issueEndAt,
            CouponValidMode validMode, Instant validStartAt, Instant validEndAt, Integer validDays,
            Integer totalQuantity, int perUserLimit) {
        ShopCoupon c = new ShopCoupon();
        c.publicToken = publicToken;
        c.kind = kind;
        c.nameId = nameId;
        c.nameEn = nameEn;
        c.amount = amount;
        c.minSpend = minSpend;
        c.scopeType = scopeType;
        c.scopeValues = new ArrayList<>(scopeValues);
        c.acquireType = acquireType;
        c.issueStartAt = issueStartAt;
        c.issueEndAt = issueEndAt;
        c.validMode = validMode;
        c.validStartAt = validStartAt;
        c.validEndAt = validEndAt;
        c.validDays = validDays;
        c.totalQuantity = totalQuantity;
        c.perUserLimit = perUserLimit;
        return c;
    }

    /** 发放期左闭右开 {@code [issue_start_at, issue_end_at)} 且 ACTIVE：能领 / 能兑 / 新人能发。 */
    public boolean isIssuableAt(Instant now) {
        return status == CouponStatus.ACTIVE && !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
    }

    @PrePersist
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

    public String getPublicToken() {
        return publicToken;
    }

    public CouponKind getKind() {
        return kind;
    }

    public String getNameId() {
        return nameId;
    }

    public String getNameEn() {
        return nameEn;
    }

    public long getAmount() {
        return amount;
    }

    public long getMinSpend() {
        return minSpend;
    }

    public CouponScopeType getScopeType() {
        return scopeType;
    }

    public List<String> getScopeValues() {
        return scopeValues == null ? List.of() : List.copyOf(scopeValues);
    }

    public CouponAcquireType getAcquireType() {
        return acquireType;
    }

    public Instant getIssueStartAt() {
        return issueStartAt;
    }

    public Instant getIssueEndAt() {
        return issueEndAt;
    }

    public CouponValidMode getValidMode() {
        return validMode;
    }

    public Instant getValidStartAt() {
        return validStartAt;
    }

    public Instant getValidEndAt() {
        return validEndAt;
    }

    public Integer getValidDays() {
        return validDays;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public int getIssuedCount() {
        return issuedCount;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public CouponStatus getStatus() {
        return status;
    }

    public boolean isVoidOnStop() {
        return voidOnStop;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

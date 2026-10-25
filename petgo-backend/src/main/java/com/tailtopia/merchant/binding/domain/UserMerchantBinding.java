package com.tailtopia.merchant.binding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * App 用户与门店的绑定（契约第 2 节，FR-7）。一个用户一行，<b>永不更新</b>：绑定后不能换店、不能解绑。
 *
 * <p>注销（FR-41）不删本行：{@code users} 软删、绑定保留，商家平台的绑定用户总数不减、返佣照常。
 */
@Entity
@Table(name = "user_merchant_bindings")
public class UserMerchantBinding {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** {@code merchant_stores.id}（跨仓库无外键）。 */
    @Column(name = "merchant_store_id", nullable = false, updatable = false)
    private Long merchantStoreId;

    @Column(name = "bound_at", nullable = false, updatable = false)
    private Instant boundAt;

    protected UserMerchantBinding() {
    }

    public static UserMerchantBinding of(long userId, long merchantStoreId, Instant boundAt) {
        UserMerchantBinding b = new UserMerchantBinding();
        b.userId = userId;
        b.merchantStoreId = merchantStoreId;
        b.boundAt = boundAt;
        return b;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getMerchantStoreId() {
        return merchantStoreId;
    }

    public Instant getBoundAt() {
        return boundAt;
    }
}

package com.tailtopia.pay.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * PawCoin 钱包（Story 1.2，建 {@code pawcoin_wallets} 表）。<b>并发非负</b>（FR-NFR-3）：余额变更一律走
 * {@code PawCoinWalletRepository.applyDelta} 原子条件 UPDATE（{@code WHERE balance + :delta >= 0}），
 * 库级 {@code CHECK(balance>=0)} 兜底。<b>禁应用层「读-改-写」</b>（并发丢更新）。
 *
 * <p>余额无有效期；{@code user_id} 唯一（一人一钱包）。{@code @Version} 供偶发实体级读改写路径的乐观锁
 * （主路径已是原子 UPDATE，无需重试）。
 */
@Entity
@Table(name = "pawcoin_wallets")
public class PawCoinWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PawCoinWallet() {
    }

    public static PawCoinWallet forUser(long userId) {
        PawCoinWallet w = new PawCoinWallet();
        w.userId = userId;
        w.balance = 0;
        return w;
    }

    @PrePersist
    void onCreate() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public long getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 定价配置（Story 9.2，AB-8A/8F）。固定单行 {@code id=1}（DB CHECK 保证）。种子 = 现网 env 默认。
 * 改值只影响后续新成交（历史订单已落快照）。
 */
@Entity
@Table(name = "pricing_config")
public class PricingConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "vet_consult_price", nullable = false)
    private long vetConsultPrice;

    @Column(name = "vet_share_rate", nullable = false)
    private int vetShareRate;

    @Column(name = "ai_unlock_price", nullable = false)
    private long aiUnlockPrice;

    @Column(name = "id_hd_download_price", nullable = false)
    private long idHdDownloadPrice;

    @Column(name = "monthly_free_quota", nullable = false)
    private int monthlyFreeQuota;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PricingConfig() {
    }

    /** 兽医到手 = 单价 * 分成 / 100（架构 §3.2）。 */
    public long vetPayout() {
        return vetConsultPrice * vetShareRate / 100;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getVetConsultPrice() {
        return vetConsultPrice;
    }

    public void setVetConsultPrice(long v) {
        this.vetConsultPrice = v;
    }

    public int getVetShareRate() {
        return vetShareRate;
    }

    public void setVetShareRate(int v) {
        this.vetShareRate = v;
    }

    public long getAiUnlockPrice() {
        return aiUnlockPrice;
    }

    public void setAiUnlockPrice(long v) {
        this.aiUnlockPrice = v;
    }

    public long getIdHdDownloadPrice() {
        return idHdDownloadPrice;
    }

    public void setIdHdDownloadPrice(long v) {
        this.idHdDownloadPrice = v;
    }

    public int getMonthlyFreeQuota() {
        return monthlyFreeQuota;
    }

    public void setMonthlyFreeQuota(int v) {
        this.monthlyFreeQuota = v;
    }
}

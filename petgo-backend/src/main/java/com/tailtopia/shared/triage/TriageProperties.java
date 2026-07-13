package com.tailtopia.shared.triage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 问诊配置（Story 2.1）。前缀 {@code petgo.triage}。绑定见 {@link TriageConfig}
 * （{@code @EnableConfigurationProperties}，照 {@code ImConfig}/{@code PayConfig} 范式）。
 *
 * <p>{@code defaultFreeQuota}：每月免费解锁 AI 详情的默认次数（env {@code TRIAGE_DEFAULT_FREE_QUOTA}，
 * 默认 1）。读取处（{@code FreeQuotaService}）clamp 到 {@code [0,35]}（架构 §9「后台可调 0-35」，
 * 0=全付费无免费额度，合法）。<b>Epic 9（9-2）后台 {@code pricing_config.monthly_free_quota} 落地后换 DB 读</b>
 * ——本 story 先 env（照 1-3 {@code TopupTierProvider} / 1-5 {@code PAWCOIN_TOPUP_PAUSED} 渐进模式）。
 */
@ConfigurationProperties(prefix = "petgo.triage")
public class TriageProperties {

    /** 每月免费解锁次数默认值（env 注入，默认 1；读取处 clamp [0,35]）。 */
    private int defaultFreeQuota = 1;

    public int getDefaultFreeQuota() {
        return defaultFreeQuota;
    }

    public void setDefaultFreeQuota(int defaultFreeQuota) {
        this.defaultFreeQuota = defaultFreeQuota;
    }
}

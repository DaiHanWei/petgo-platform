package com.tailtopia.shared.consult;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 兽医咨询计费配置（Story 3.4）。前缀 {@code petgo.consult}。绑定见 {@link ConsultConfig}
 * （{@code @EnableConfigurationProperties}，照 {@code TriageConfig}/{@code PayConfig} 范式）。
 *
 * <p>{@code unitPrice}：单次兽医咨询价（env {@code CONSULT_UNIT_PRICE}，默认 50000 = Rp50,000）。
 * PawCoin 花费 = 此值（1 koin=Rp1）；现金 amount = 此值 IDR。成交价落 {@code consult_orders} 快照。
 * {@code vetShareRate}：兽医分成比例（env {@code CONSULT_VET_SHARE_RATE}，默认 60 = 60%）；
 * {@link #vetPayout()} 派生兽医到手 = {@code unitPrice * rate / 100}（默认 30000，架构 §3.2 例）。
 *
 * <p>{@code maxRebroadcast}/{@code requestMaxAgeMinutes}：支付窗超时回队重播上限（Story 3.4 补 3-3 缺口）——
 * `rebroadcast_count` 达上限 或 请求存活超龄 → 不再回队、彻底失败落 {@code failed_consult_requests}(TIMEOUT)。
 *
 * <p><b>Epic 9（9-2）后台 {@code pricing_config}（vet_consult_price / vet_share_rate）落地后换 DB 读</b>
 * ——本 story 先 env（照 2-1/2-3 {@code TriageProperties} 渐进模式）。
 */
@ConfigurationProperties(prefix = "petgo.consult")
public class ConsultProperties {

    /** 单次兽医咨询价（env 注入，默认 50000 = Rp50,000；PawCoin 花费同值，现金同额 IDR）。 */
    private long unitPrice = 50000L;

    /** 兽医分成比例（env 注入，默认 60 = 60%）。 */
    private int vetShareRate = 60;

    /** 支付窗超时回队重播次数上限（达到 → 彻底失败，不再回队）。 */
    private int maxRebroadcast = 5;

    /** 请求存活上限（分钟；超龄 → 彻底失败，不再回队，防没人付时无限重播）。 */
    private int requestMaxAgeMinutes = 30;

    /** 兽医到手金额（成交时快照进订单）= 单价 × 分成 / 100。 */
    public long vetPayout() {
        return unitPrice * vetShareRate / 100;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(long unitPrice) {
        this.unitPrice = unitPrice;
    }

    public int getVetShareRate() {
        return vetShareRate;
    }

    public void setVetShareRate(int vetShareRate) {
        this.vetShareRate = vetShareRate;
    }

    public int getMaxRebroadcast() {
        return maxRebroadcast;
    }

    public void setMaxRebroadcast(int maxRebroadcast) {
        this.maxRebroadcast = maxRebroadcast;
    }

    public int getRequestMaxAgeMinutes() {
        return requestMaxAgeMinutes;
    }

    public void setRequestMaxAgeMinutes(int requestMaxAgeMinutes) {
        this.requestMaxAgeMinutes = requestMaxAgeMinutes;
    }
}

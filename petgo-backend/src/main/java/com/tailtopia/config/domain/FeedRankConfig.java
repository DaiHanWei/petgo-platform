package com.tailtopia.config.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 首页推荐算法打分参数（V1.1.6 Story 16.4，FR-95）。固定单行 {@code id=1}。
 *
 * <p>🔴 <b>外化不是优化项</b>：FR-95 与首页点赞同批发版 ⇒ 开发阶段线上不存在
 * {@code source=feed} 的点赞数据 ⇒ 参数<b>第一次校准必然在发版之后</b>（OQ-B1）。
 * 写死就意味着那次校准要走一次完整发版流程。
 *
 * <p>⚠️ <b>荣誉加成（1.3）刻意不在本表</b>：它的唯一事实源是
 * {@code ContentTagQueryService.RANK_WEIGHT_MULTIPLIER}，再存一份就会出现
 * 「改了一处没改另一处」，而那不会报错。
 *
 * <p>🔄 <b>限流系数（0.2）在 Story 17.1 加进了本表</b>，反转了 16.4 写的「后者归 Epic 17」。
 * 那句当时的意思是「延后」而不是「不该在这里」：限流系数<b>没有第二个事实源</b>，
 * 而它就是同一个打分公式里的一个乘法因子 —— 放进同一张单行表可以直接复用
 * FEED_RANK 的 diff 审计与配置页，不新增配置类型。
 *
 * <p>{@link #getInteractionP95()} 是<b>动态值</b>（近 30 天 95 分位，定期重算），
 * 不是运营手填的常数；{@code 0} = 尚未算出。
 */
@Entity
@Table(name = "feed_rank_config")
public class FeedRankConfig {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "freshness_weight", nullable = false)
    private double freshnessWeight;

    @Column(name = "interaction_weight", nullable = false)
    private double interactionWeight;

    @Column(name = "comment_weight", nullable = false)
    private double commentWeight;

    @Column(name = "interaction_p95", nullable = false)
    private double interactionP95;

    @Column(name = "exposure_decay", nullable = false)
    private double exposureDecay;

    /** 刷新抖动幅度 0–1（2026-09-01）：0=关闭（纯分数排序），越大下拉刷新换得越狠。 */
    @Column(name = "shuffle_strength", nullable = false)
    private double shuffleStrength;

    @Column(name = "seen_window_days", nullable = false)
    private int seenWindowDays;

    @Column(name = "window_size", nullable = false)
    private int windowSize;

    @Column(name = "attr_fun_quota", nullable = false)
    private int attrFunQuota;

    @Column(name = "attr_edu_quota", nullable = false)
    private int attrEduQuota;

    @Column(name = "attr_life_quota", nullable = false)
    private int attrLifeQuota;

    @Column(name = "species_main_quota", nullable = false)
    private int speciesMainQuota;

    @Column(name = "species_other_quota", nullable = false)
    private int speciesOtherQuota;

    @Column(name = "species_general_quota", nullable = false)
    private int speciesGeneralQuota;

    /**
     * 限流（降权）系数（Story 17.1 · AC5）。默认 0.2，🛡 <b>平台级、不是逐条可调</b>。
     *
     * <p>🛡 建表 CHECK 夹在 {@code (0, 1)} 开区间，两头都是有意的：
     * ≥ 1 不是降权（等于没处置）；= 0 会让分数恒为 0 ⇒ 永远排不进推荐序 ⇒
     * 事实上等于从首页下架，而 17.1 的 AC2 明令「降权不是下架」。
     */
    @Column(name = "throttle_factor", nullable = false)
    private double throttleFactor;

    @Column(name = "p95_recomputed_at")
    private Instant p95RecomputedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FeedRankConfig() {
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public double getFreshnessWeight() {
        return freshnessWeight;
    }

    public void setFreshnessWeight(double v) {
        this.freshnessWeight = v;
    }

    public double getInteractionWeight() {
        return interactionWeight;
    }

    public void setInteractionWeight(double v) {
        this.interactionWeight = v;
    }

    public double getCommentWeight() {
        return commentWeight;
    }

    public void setCommentWeight(double v) {
        this.commentWeight = v;
    }

    public double getInteractionP95() {
        return interactionP95;
    }

    public void setInteractionP95(double v) {
        this.interactionP95 = v;
    }

    public double getThrottleFactor() {
        return throttleFactor;
    }

    public void setThrottleFactor(double v) {
        this.throttleFactor = v;
    }

    public double getExposureDecay() {
        return exposureDecay;
    }

    public void setExposureDecay(double v) {
        this.exposureDecay = v;
    }

    public double getShuffleStrength() {
        return shuffleStrength;
    }

    public void setShuffleStrength(double v) {
        this.shuffleStrength = v;
    }

    public int getSeenWindowDays() {
        return seenWindowDays;
    }

    public void setSeenWindowDays(int v) {
        this.seenWindowDays = v;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int v) {
        this.windowSize = v;
    }

    public int getAttrFunQuota() {
        return attrFunQuota;
    }

    public void setAttrFunQuota(int v) {
        this.attrFunQuota = v;
    }

    public int getAttrEduQuota() {
        return attrEduQuota;
    }

    public void setAttrEduQuota(int v) {
        this.attrEduQuota = v;
    }

    public int getAttrLifeQuota() {
        return attrLifeQuota;
    }

    public void setAttrLifeQuota(int v) {
        this.attrLifeQuota = v;
    }

    public int getSpeciesMainQuota() {
        return speciesMainQuota;
    }

    public void setSpeciesMainQuota(int v) {
        this.speciesMainQuota = v;
    }

    public int getSpeciesOtherQuota() {
        return speciesOtherQuota;
    }

    public void setSpeciesOtherQuota(int v) {
        this.speciesOtherQuota = v;
    }

    public int getSpeciesGeneralQuota() {
        return speciesGeneralQuota;
    }

    public void setSpeciesGeneralQuota(int v) {
        this.speciesGeneralQuota = v;
    }

    public Instant getP95RecomputedAt() {
        return p95RecomputedAt;
    }

    public void setP95RecomputedAt(Instant v) {
        this.p95RecomputedAt = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

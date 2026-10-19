package com.tailtopia.growth.web.dto;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.List;

/**
 * 等级页首屏（V1.4.0 ① Story 1.4 · AD-16）。<b>只下发代码与数字</b>，规则名 / 段位名 / 权益文案全由 App 本地化。
 * 不参与者只有 {@code participating = false}，其余字段为 null（全局 NON_NULL 省略）。满级时 {@code nextLevel} /
 * {@code nextThreshold} 为 null。
 */
public record GrowthPageResponse(
        boolean participating,
        Integer level,
        GrowthTier tier,
        Long lifetimeTotal,
        Long currentThreshold,
        Integer nextLevel,
        Long nextThreshold,
        List<LevelRow> levels,
        Frames frames,
        List<RuleRow> rules,
        PassiveGroup passiveGroup,
        List<TierBenefits> tierBenefits,
        List<GrowthLedgerRow> recentLedger) {

    public static GrowthPageResponse notParticipating() {
        return new GrowthPageResponse(false, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 等级表一行（App 据此算段位区间与每级颜色）。 */
    public record LevelRow(int level, long threshold, GrowthTier tier) {
    }

    /** 头像框四档。 */
    public record Frames(FrameChoice effective, boolean manual, List<FrameOption> options) {
    }

    /** {@code unlockLevel} 从等级表算（该档对应段位的首级），不写死。 */
    public record FrameOption(FrameChoice frame, boolean unlocked, int unlockLevel) {
    }

    /**
     * 一条规则的配置与今日进度。
     *
     * @param todayCount  有每日上限的主动规则：今日已用次数；被动 / 无上限规则为 null
     * @param todayEarned 今日该规则正数流水之和（扣回不减）
     * @param maxedToday  今日已满
     */
    public record RuleRow(String code, int points, Integer unitAmount, Integer dailyCap, String capGroup,
            Integer todayCount, long todayEarned, boolean maxedToday) {
    }

    /** 被动三项当天合计。 */
    public record PassiveGroup(Integer dailyCap, int todayEarned, boolean maxedToday) {
    }

    /** 段位权益；最高段位 {@code toLevel = null}（「Lv15+」）。 */
    public record TierBenefits(GrowthTier tier, int fromLevel, Integer toLevel, List<GrowthBenefit> benefits) {
    }
}

package com.tailtopia.growth.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tailtopia.growth.domain.GrowthBenefit;
import com.tailtopia.growth.domain.GrowthTier;
import java.util.List;

/**
 * 待弹的升级庆祝 / 存量汇总（V1.4.0 ① Story 2.1 · AD-11）。两个字段无待弹时为 {@code null}（显式输出 null）。
 * 只下发代码与数字；段位名 / 权益文案由 App 本地化。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GrowthPopupsResponse(CelebrationView celebration, BackfillView backfill) {

    public static GrowthPopupsResponse none() {
        return new GrowthPopupsResponse(null, null);
    }

    /**
     * 升级庆祝。{@code toTier} / {@code lifetimeTotal} 为对 AD-11 的补充（卡片显示段位与累计 EXP）。
     *
     * @param tierUp           from 与 to 段位不同（跨段位 → App 用全屏形态）
     * @param unlockedBenefits 跨段位时为最终段位的权益代码；段位内为空
     * @param frameAction      {@code NONE} / {@code AUTO}（已自动戴上）/ {@code OFFER_NEW_FRAME}（手动选过，提示戴新框）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CelebrationView(int fromLevel, int toLevel, boolean tierUp, GrowthTier toTier, long lifetimeTotal,
            List<GrowthBenefit> unlockedBenefits, FrameAction frameAction) {
    }

    /** 存量补算汇总（Story 2.2 填充）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BackfillView(int level, GrowthTier tier, long activeDays, long milestoneCount, long milestoneExp,
            long total) {
    }

    public enum FrameAction {
        NONE,
        AUTO,
        OFFER_NEW_FRAME
    }
}

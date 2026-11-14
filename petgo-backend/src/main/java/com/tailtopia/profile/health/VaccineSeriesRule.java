package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;

/**
 * 疫苗系列规则（V1.5.0 Story 1.2，PRD FR-159 两张表、附录 B.1，架构增量 §3.1 / §3.2）。纯函数。
 *
 * <p>Next date 表<b>自上而下第一条命中</b>：单剂 → +1 年；Puppy DP 剂次 2 或空（&gt;2 当 2）→ +3 周、到期 Combo 第 1 剂；
 * 剂次 &lt; 系列长度 → +4 周；剂次 ≥ 系列长度 → +1 年；空剂次 young &lt;16 周 → +4 周；空剂次 young ≥16 周 → +1 年；
 * 空剂次 adult → +1 年。年龄组按<b>该剂自己的 event date</b> 判。
 */
public final class VaccineSeriesRule {

    private static final int BLANK_DOSE_SERIES_WEEKS = 16;

    private VaccineSeriesRule() {
    }

    /**
     * @param petHasPuppyDpRecord 宠物有任一 Puppy DP 记录（Combo 的 young 系列长度因此为 2）
     * @return {@code OTHER} / 无预设 → null（无预填）
     */
    public static NextDateSuggestion next(VaccinePreset preset, Integer dose, LocalDate eventDate, LocalDate birthday,
            boolean petHasPuppyDpRecord) {
        if (preset == null || preset == VaccinePreset.OTHER || eventDate == null) {
            return null;
        }
        if (preset.singleDose()) {
            return new NextDateSuggestion(eventDate.plusYears(1), preset, null);
        }
        if (preset == VaccinePreset.PUPPY_DP && (dose == null || dose >= 2)) {
            return new NextDateSuggestion(eventDate.plusWeeks(3), VaccinePreset.COMBO_DHPPI_L, 1);
        }
        boolean young = HealthDates.isYoung(birthday, eventDate);
        if (dose != null) {
            int length = seriesLength(preset, young, petHasPuppyDpRecord);
            if (dose < length) {
                return new NextDateSuggestion(eventDate.plusWeeks(4), preset, dose + 1);
            }
            return new NextDateSuggestion(eventDate.plusYears(1), preset, null);
        }
        if (young && HealthDates.isUnderWeeks(birthday, eventDate, BLANK_DOSE_SERIES_WEEKS)) {
            return new NextDateSuggestion(eventDate.plusWeeks(4), preset, null);
        }
        return new NextDateSuggestion(eventDate.plusYears(1), preset, null);
    }

    /** 系列长度（首免剂数），PRD FR-159 Series length 表。单剂 / OTHER 返回 1。 */
    public static int seriesLength(VaccinePreset preset, boolean young, boolean petHasPuppyDpRecord) {
        return switch (preset) {
            case F3, F4 -> young ? 3 : 2;
            case COMBO_DHPPI_L -> young && !petHasPuppyDpRecord ? 3 : 2;
            case FELV, LEPTO, PUPPY_DP -> 2;
            default -> 1;
        };
    }
}

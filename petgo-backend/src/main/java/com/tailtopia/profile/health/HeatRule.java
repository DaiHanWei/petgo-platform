package com.tailtopia.profile.health;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 母狗发情 Next date（V1.5.0 Story 1.2，PRD FR-171 Average gap、附录 B.1）。纯函数。
 *
 * <p>最近 3 次发情（各周期首日）的间隔（1 或 2 个），丢弃 &lt;120 天，有剩余取均值（四舍五入到天）加到最近一次首日；
 * 否则 +6 个月；结果钳在 [+4 个月, +12 个月]。只对 DOG + FEMALE + 未绝育的周期首条给值（由调用方判定）。
 */
public final class HeatRule {

    private static final int MIN_GAP_DAYS = 120;

    private HeatRule() {
    }

    /** @param episodeFirstDays 各周期首日，升序；最后一个 = 本次（最近一次）。空 → null。 */
    public static LocalDate nextForFemaleDog(List<LocalDate> episodeFirstDays) {
        if (episodeFirstDays == null || episodeFirstDays.isEmpty()) {
            return null;
        }
        List<LocalDate> last3 = episodeFirstDays.subList(Math.max(0, episodeFirstDays.size() - 3),
                episodeFirstDays.size());
        LocalDate latest = last3.get(last3.size() - 1);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < last3.size(); i++) {
            long gap = ChronoUnit.DAYS.between(last3.get(i - 1), last3.get(i));
            if (gap >= MIN_GAP_DAYS) {
                gaps.add(gap);
            }
        }
        LocalDate next;
        if (gaps.isEmpty()) {
            next = latest.plusMonths(6);
        } else {
            long sum = gaps.stream().mapToLong(Long::longValue).sum();
            next = latest.plusDays(Math.round((double) sum / gaps.size()));
        }
        LocalDate min = latest.plusMonths(4);
        LocalDate max = latest.plusMonths(12);
        if (next.isBefore(min)) {
            return min;
        }
        return next.isAfter(max) ? max : next;
    }
}

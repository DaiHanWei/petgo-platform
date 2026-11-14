package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import java.time.LocalDate;

/**
 * 驱虫间隔（V1.5.0 Story 1.2，PRD FR-160、FR-163 表、附录 B.1）。纯函数。
 *
 * <p>INTERNAL 按 event date 时年龄：&lt;8 周 +14 天 / 8 周–&lt;6 个月 +1 个月 / ≥6 个月或无生日 +3 个月；
 * EXTERNAL +产品时长（缺省 1）；FLEA_WORM +1 个月；HEARTWORM +1 个月；未分型（Legacy）+3 个月。
 */
public final class DewormRule {

    private DewormRule() {
    }

    public static LocalDate next(DewormType type, Integer durationMonths, LocalDate eventDate, LocalDate birthday) {
        if (eventDate == null) {
            return null;
        }
        if (type == null) {
            return eventDate.plusMonths(3); // Legacy（FR-168）
        }
        return switch (type) {
            case INTERNAL -> {
                if (HealthDates.isUnderWeeks(birthday, eventDate, 8)) {
                    yield eventDate.plusDays(14);
                }
                yield HealthDates.isYoung(birthday, eventDate) ? eventDate.plusMonths(1) : eventDate.plusMonths(3);
            }
            case EXTERNAL -> eventDate.plusMonths(durationMonths == null ? 1 : durationMonths);
            case FLEA_WORM, HEARTWORM -> eventDate.plusMonths(1);
        };
    }
}

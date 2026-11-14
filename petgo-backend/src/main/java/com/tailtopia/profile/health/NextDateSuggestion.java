package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;

/**
 * 预填结果（V1.5.0 Story 1.2）：Next date + 到期疫苗 / 到期剂次（疫苗以外恒 null）。
 * 到期疫苗 / 剂次是「event date 当时命中的分支」，即使用户后来手改了 Next date 也不变（PRD FR-159 Due vaccine）。
 */
public record NextDateSuggestion(LocalDate nextDate, VaccinePreset dueVaccinePreset, Integer dueDose) {

    public static NextDateSuggestion of(LocalDate nextDate) {
        return new NextDateSuggestion(nextDate, null, null);
    }
}

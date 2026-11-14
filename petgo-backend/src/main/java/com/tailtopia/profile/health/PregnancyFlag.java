package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 怀孕 / 哺乳标记的生效判定（V1.5.0 Story 1.3，PRD FR-162 Auto-clear，架构增量 §1.2 / §4.6）。纯函数。
 *
 * <p>自动关闭日 = 设为「是」那一刻的 WIB 日期 + 4 个日历月（月末钳制：1 月 31 日 → 5 月 31 日）。当天日跑开头视为
 * 00:00 WIB 清除；再次设为「是」重新起算。
 */
public final class PregnancyFlag {

    private static final int AUTO_OFF_MONTHS = 4;

    private PregnancyFlag() {
    }

    public static LocalDate autoOffDate(Instant setAt) {
        return setAt == null ? null : HealthDates.toWibDate(setAt).plusMonths(AUTO_OFF_MONTHS);
    }

    /** 该宠物在 {@code day}（WIB）是否处于怀孕 / 哺乳：标记为是（sex=MALE 恒否）且尚未到自动关闭日。 */
    public static boolean isActiveOn(PetProfile pet, LocalDate day) {
        if (!pet.isPregnantNursingEffective()) {
            return false;
        }
        LocalDate off = autoOffDate(pet.getPregnantSetAt());
        return off == null || day.isBefore(off);
    }

    /**
     * 到 {@code day} 应被自动清除：标记仍为是（落库值）且自动关闭日 ≤ day。缺 {@code pregnant_set_at} 的脏数据
     * 算不出关闭日 → 一并清掉（否则会永远处于怀孕态、提醒永远暂停）。
     */
    public static boolean expiredOn(PetProfile pet, LocalDate day) {
        if (!pet.isPregnantNursing()) {
            return false;
        }
        LocalDate off = autoOffDate(pet.getPregnantSetAt());
        return off == null || !day.isBefore(off);
    }
}

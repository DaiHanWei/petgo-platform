package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 宠物类型修改冷却（V1.5.0 pet-species FR-207 · 架构 D-8）：纯函数，无状态。
 *
 * <p>口径：{@code changeableFrom = WIB日期(pet_type_changed_at) + 90 天}；{@code todayWib >= changeableFrom} 即可改
 * （「第 91 天可改」：2026-11-24 改 → 2027-02-22 可改）。{@code todayWib} 由调用方传入，便于单测。
 */
public final class PetSpeciesCooldown {

    /** 冷却天数。 */
    public static final int COOLDOWN_DAYS = 90;

    private PetSpeciesCooldown() {
    }

    /** 某次修改之后最早可再改的 WIB 日期。 */
    public static LocalDate changeableFrom(Instant changedAt) {
        return LocalDate.ofInstant(changedAt, ScheduleWindow.WIB).plusDays(COOLDOWN_DAYS);
    }

    /** 冷却中 → 返回可改日期；从未改过或已过冷却 → null。 */
    public static LocalDate changeableFromOrNull(PetProfile p, LocalDate todayWib) {
        Instant changedAt = p.getPetTypeChangedAt();
        if (changedAt == null) {
            return null;
        }
        LocalDate from = changeableFrom(changedAt);
        return todayWib.isBefore(from) ? from : null;
    }

    /** 当前 WIB 日期。 */
    public static LocalDate todayWib() {
        return LocalDate.now(ScheduleWindow.WIB);
    }
}

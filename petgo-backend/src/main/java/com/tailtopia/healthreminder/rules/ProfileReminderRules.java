package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 档案类建议（V1.5.0 Story 2.2，PRD FR-164 表、Late triggers，架构增量 §4.5）。纯函数，只 CAT / DOG。
 *
 * <table>
 *   <tr><th>种类</th><th>normal date</th><th>跳过</th></tr>
 *   <tr><td>FIRST_VACCINE</td><td>生日 + 42 天；建档时已 6 周–6 个月 → 建档后的下一跑</td>
 *       <td>有疫苗记录、无生日、normal 已满 6 个月</td></tr>
 *   <tr><td>FIRST_DEWORM</td><td>建档日 + 7 天</td><td>有驱虫记录</td></tr>
 *   <tr><td>SPAY_NEUTER</td><td>生日 + 5 个月（猫）/ 6 个月（狗）；建档时已过 → 建档日 + 14 天；
 *       设性别晚 → max(上述, 设性别后的下一跑)</td><td>已绝育 / 有绝育记录 / 无生日 / sex 空 / 已满 12 个月</td></tr>
 *   <tr><td>CHECKUP</td><td>{@link CheckupDueRule}</td><td>—</td></tr>
 * </table>
 * 一次性三类已发过（任一 origin）不再生成；normal date 早于该用户上线日的不生成（归上线排队）。
 */
public final class ProfileReminderRules {

    private static final int FIRST_VACCINE_DAYS = 42;
    private static final int FIRST_DEWORM_DAYS = 7;
    private static final int LATE_SPAY_DAYS = 14;

    private ProfileReminderRules() {
    }

    public static List<DesiredReminder> desired(PetReminderContext ctx, LocalDate userLaunchDate) {
        return desired(ctx, userLaunchDate, null);
    }

    /**
     * @param fromDate 下一跑（可空）。体检到期日不早于上线日、却已早于下一跑（如补录了很早的到访）→ 改为下一跑发，
     *                 否则该周期永远排不上、之后再无体检提醒。
     */
    public static List<DesiredReminder> desired(PetReminderContext ctx, LocalDate userLaunchDate,
            LocalDate fromDate) {
        List<DesiredReminder> out = new ArrayList<>();
        PetHealthSnapshot pet = ctx.pet();
        if (userLaunchDate == null || !pet.isCatOrDog() || pet.status() == PetStatus.READ_ONLY) {
            return out;
        }
        add(out, ctx, ReminderKind.FIRST_VACCINE, firstVaccineNormal(ctx), userLaunchDate);
        add(out, ctx, ReminderKind.FIRST_DEWORM, firstDewormNormal(ctx), userLaunchDate);
        add(out, ctx, ReminderKind.SPAY_NEUTER_SUGGESTION, spayNormal(ctx), userLaunchDate);
        CheckupDueRule.CheckupDue checkup = ctx.checkupDue();
        if (checkup != null && !checkup.dueDate().isBefore(userLaunchDate)) {
            LocalDate due = fromDate != null && checkup.dueDate().isBefore(fromDate) ? fromDate : checkup.dueDate();
            out.add(DesiredReminder.simple(null, ReminderKind.CHECKUP, PushPoint.NONE, ReminderOrigin.PROFILE, null,
                    due));
        }
        return out;
    }

    private static void add(List<DesiredReminder> out, PetReminderContext ctx, ReminderKind kind, LocalDate normal,
            LocalDate userLaunchDate) {
        if (normal == null || normal.isBefore(userLaunchDate) || ctx.history().sentEver(kind)) {
            return;
        }
        out.add(DesiredReminder.simple(null, kind, PushPoint.NONE, ReminderOrigin.PROFILE, null, normal));
    }

    /** 首针建议的 normal date；跳过条件成立 → null。 */
    public static LocalDate firstVaccineNormal(PetReminderContext ctx) {
        PetHealthSnapshot pet = ctx.pet();
        LocalDate birthday = pet.birthday();
        if (birthday == null || ctx.hasRecordOf(HealthRecordType.VACCINE)) {
            return null;
        }
        LocalDate sixWeeks = birthday.plusDays(FIRST_VACCINE_DAYS);
        LocalDate created = pet.createdDate();
        // 建档早于满 6 周那天 → 满 6 周当天；当天或之后建档 → 建档后的下一跑（当天建档也可能已过当天日跑）。
        LocalDate normal = created == null || created.isBefore(sixWeeks) ? sixWeeks : pet.createdNextRunDate();
        return normal.isBefore(birthday.plusMonths(HealthDates.YOUNG_MONTHS)) ? normal : null;
    }

    public static LocalDate firstDewormNormal(PetReminderContext ctx) {
        LocalDate created = ctx.pet().createdDate();
        if (created == null || ctx.hasRecordOf(HealthRecordType.DEWORM)) {
            return null;
        }
        return created.plusDays(FIRST_DEWORM_DAYS);
    }

    public static LocalDate spayNormal(PetReminderContext ctx) {
        PetHealthSnapshot pet = ctx.pet();
        LocalDate birthday = pet.birthday();
        if (birthday == null || pet.sex() == null || pet.isNeutered() || ctx.hasRecordOf(HealthRecordType.NEUTER)) {
            return null;
        }
        LocalDate trigger = birthday.plusMonths(pet.petType() == PetType.CAT ? 5 : 6);
        LocalDate created = pet.createdDate();
        // 触发日当天或之后才建档 → 建档 + 14 天（当天建档时触发日的日跑可能已过）。
        LocalDate normal = created != null && !created.isBefore(trigger) ? created.plusDays(LATE_SPAY_DAYS) : trigger;
        if (pet.sexSetAt() != null) {
            LocalDate afterSex = HealthDates.nextRunDate(pet.sexSetAt());
            if (afterSex.isAfter(normal)) {
                normal = afterSex;
            }
        }
        return normal.isBefore(birthday.plusMonths(12)) ? normal : null;
    }

    /** 绝育建议文案按性别（母 → spay，公 → neuter）。 */
    public static boolean spayCopyIsFemale(PetHealthSnapshot pet) {
        return pet.sex() == PetSex.FEMALE;
    }
}

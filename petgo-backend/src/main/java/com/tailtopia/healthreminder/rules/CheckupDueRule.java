package com.tailtopia.healthreminder.rules;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;

/**
 * 体检到期日（V1.5.0 Story 2.2，PRD FR-164「Check-up due date」原文算法，架构增量 §4.4）。纯函数，只 CAT / DOG。
 *
 * <ol>
 *   <li>base = 最近一次到访（Vet visit 记录 / 兽医问诊结束）与上一次已发体检的发送日中较晚者；都没有 → 建档日；</li>
 *   <li>无生日 → base + 12 个月；</li>
 *   <li>base 当天已 senior（猫 ≥10 岁、狗 ≥7 岁）→ base + 6 个月；</li>
 *   <li>否则 max(base + 6 个月, min(base + 12 个月, senior 生日))。</li>
 * </ol>
 * 文案只看 due 当天是否 senior，不反过来影响间隔。
 */
public final class CheckupDueRule {

    /** 到期日与文案用的 senior 标记。 */
    public record CheckupDue(LocalDate dueDate, boolean senior, LocalDate base) {
    }

    private CheckupDueRule() {
    }

    /** @return 非猫狗或无法确定 base → null */
    public static CheckupDue due(PetHealthSnapshot pet, LocalDate lastVisitDate, LocalDate lastSentCheckupDate) {
        if (!pet.isCatOrDog()) {
            return null;
        }
        LocalDate base = max(lastVisitDate, lastSentCheckupDate);
        if (base == null) {
            base = pet.createdDate();
        }
        if (base == null) {
            return null;
        }
        LocalDate birthday = pet.birthday();
        if (birthday == null) {
            return new CheckupDue(base.plusMonths(12), false, base);
        }
        LocalDate seniorBirthday = seniorBirthday(pet.petType(), birthday);
        LocalDate due;
        if (!base.isBefore(seniorBirthday)) {
            due = base.plusMonths(6);
        } else {
            LocalDate twelve = base.plusMonths(12);
            LocalDate earliest = twelve.isBefore(seniorBirthday) ? twelve : seniorBirthday;
            LocalDate six = base.plusMonths(6);
            due = earliest.isBefore(six) ? six : earliest;
        }
        return new CheckupDue(due, !due.isBefore(seniorBirthday), base);
    }

    /** 猫 10 岁、狗 7 岁生日。 */
    public static LocalDate seniorBirthday(PetType type, LocalDate birthday) {
        return birthday.plusYears(type == PetType.CAT ? 10 : 7);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}

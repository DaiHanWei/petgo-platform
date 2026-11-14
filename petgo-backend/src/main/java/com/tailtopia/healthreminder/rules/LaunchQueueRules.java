package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 上线排队（V1.5.0 Story 2.2，PRD FR-164 Launch rules / Launch queue，架构增量 §4.7）。纯函数。
 *
 * <p>只有 normal date <b>早于</b>该用户上线日、且当下仍满足条件的建议进队：首针（6 周–6 个月、无疫苗）&gt; 绝育（未满 12 个月、
 * 已过触发日、sex 已填、未绝育）&gt; 首驱虫（无驱虫）&gt; 体检（到期日早于上线日）；同类按宠物建档先后。
 */
public final class LaunchQueueRules {

    /** 一个排队项。 */
    public record QueueItem(long petId, ReminderKind kind) {
    }

    private static final List<ReminderKind> PRIORITY = List.of(ReminderKind.FIRST_VACCINE,
            ReminderKind.SPAY_NEUTER_SUGGESTION, ReminderKind.FIRST_DEWORM, ReminderKind.CHECKUP);

    private LaunchQueueRules() {
    }

    public static List<QueueItem> items(List<PetReminderContext> pets, LocalDate userLaunchDate) {
        List<PetReminderContext> ordered = new ArrayList<>(pets);
        ordered.sort(Comparator.comparing((PetReminderContext c) -> c.pet().createdAt() == null ? Instant.EPOCH
                : c.pet().createdAt()).thenComparingLong(c -> c.pet().petId()));
        List<QueueItem> out = new ArrayList<>();
        for (ReminderKind kind : PRIORITY) {
            for (PetReminderContext ctx : ordered) {
                if (qualifiesAtLaunch(kind, ctx, userLaunchDate)) {
                    out.add(new QueueItem(ctx.pet().petId(), kind));
                }
            }
        }
        return out;
    }

    static boolean qualifiesAtLaunch(ReminderKind kind, PetReminderContext ctx, LocalDate launch) {
        PetHealthSnapshot pet = ctx.pet();
        if (!pet.isCatOrDog() || pet.status() == PetStatus.READ_ONLY || ctx.history().sentEver(kind)
                && kind != ReminderKind.CHECKUP) {
            return false;
        }
        return switch (kind) {
            case FIRST_VACCINE -> {
                LocalDate normal = ProfileReminderRules.firstVaccineNormal(ctx);
                yield normal != null && normal.isBefore(launch) && youngForFirstVaccine(pet, launch);
            }
            case SPAY_NEUTER_SUGGESTION -> {
                LocalDate normal = ProfileReminderRules.spayNormal(ctx);
                yield normal != null && normal.isBefore(launch) && launch.isBefore(pet.birthday().plusMonths(12));
            }
            case FIRST_DEWORM -> {
                LocalDate normal = ProfileReminderRules.firstDewormNormal(ctx);
                yield normal != null && normal.isBefore(launch);
            }
            case CHECKUP -> {
                CheckupDueRule.CheckupDue due = ctx.checkupDue();
                yield due != null && due.dueDate().isBefore(launch);
            }
            default -> false;
        };
    }

    /**
     * 发送日再校验（PRD FR-164「Checked again on the send day」）：跳过条件成立 → 返回作废原因（只读 → read_only，其余
     * queue_recheck）；首针遇怀孕 / 已满 6 个月也作废。仍有效 → 空。
     */
    public static Optional<CancelReason> recheck(ReminderKind kind, PetReminderContext ctx, LocalDate day) {
        PetHealthSnapshot pet = ctx.pet();
        if (pet.status() == PetStatus.READ_ONLY) {
            return Optional.of(CancelReason.READ_ONLY);
        }
        boolean ok = switch (kind) {
            case FIRST_VACCINE -> !ctx.hasRecordOf(HealthRecordType.VACCINE) && !pet.pregnantNursing()
                    && pet.birthday() != null && day.isBefore(pet.birthday().plusMonths(HealthDates.YOUNG_MONTHS));
            case SPAY_NEUTER_SUGGESTION -> pet.sex() != null && !pet.isNeutered()
                    && !ctx.hasRecordOf(HealthRecordType.NEUTER) && pet.birthday() != null
                    && day.isBefore(pet.birthday().plusMonths(12));
            case FIRST_DEWORM -> !ctx.hasRecordOf(HealthRecordType.DEWORM);
            case CHECKUP -> {
                CheckupDueRule.CheckupDue due = ctx.checkupDue();
                yield due != null && !due.dueDate().isAfter(day); // 期间有到访 → 到期日后移 → 作废
            }
            default -> true;
        };
        return ok ? Optional.empty() : Optional.of(CancelReason.QUEUE_RECHECK);
    }

    private static boolean youngForFirstVaccine(PetHealthSnapshot pet, LocalDate launch) {
        LocalDate b = pet.birthday();
        return b != null && !launch.isBefore(b.plusDays(42)) && launch.isBefore(b.plusMonths(HealthDates.YOUNG_MONTHS));
    }
}

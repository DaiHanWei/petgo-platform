package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderFamily;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.rules.CheckupDueRule;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 每宠物每日决策（V1.5.0 Story 2.3，架构增量 §5.2，PRD FR-166 全部）。纯函数：入参是当天该宠物的 PENDING 行与上下文，
 * 出参是发送组 / 取消 / 顺延 / 挂起，不写库。
 *
 * <ol>
 *   <li>只读 → 全部 read_only；怀孕：疫苗类、子宫蓄脓、母狗发情 D-7 → paused，首针建议挂起到次日（不计顺延）；</li>
 *   <li>档案类建议发送日再校验（有记录 → satisfied，超龄等 → rebuilt）；</li>
 *   <li>同类（疫苗 / 驱虫 / Vet visit / Custom）同推送点合并为一组；</li>
 *   <li>档 1–2 全发；档 3–8：生日 / 纪念日占用 → 不可移动的 skipped_scheduled_push、可移动的顺延；否则选一个赢家
 *       （档位小 → 不可移动 → Next date 早 → 目录序），输家不可移动 → skipped_cap、可移动 → 顺延；召回日只让档 8 顺延；</li>
 *   <li>顺延：D+1 起第一个「该宠物无 PENDING 行、非生日 / 纪念日（档 8 再加非召回日）」的日子，超过原定日 + 3 → expired_defer。</li>
 * </ol>
 */
public final class HealthReminderPlanner {

    /** 生日 / 纪念日 / 召回占用时的 {@code displaced_by}（PRD §4）。 */
    public static final String SCHEDULED_PUSH = "scheduled_push";
    static final int MAX_DEFER_DAYS = 3;

    /** 当天一只宠物的输入。 */
    public record PetDayInput(LocalDate day, PetHealthSnapshot pet, boolean pregnantActive,
            List<HealthReminder> todayRows, PetReminderContext context, boolean scheduledPushToday,
            boolean recallToday, Set<LocalDate> futurePendingDays, Set<LocalDate> futureScheduledPushDays,
            Set<LocalDate> futureRecallDays) {
    }

    /** 一条取消。 */
    public record Cancel(HealthReminder row, CancelReason reason, String displacedBy, Long byRecordId) {
    }

    /** 一条改期（顺延或挂起）。 */
    public record Move(HealthReminder row, LocalDate to, boolean countsAsDeferral) {
    }

    /** 决策结果。 */
    public record PetDayDecision(List<SendGroup> sendGroups, List<Cancel> cancels, List<Move> moves) {
    }

    private HealthReminderPlanner() {
    }

    public static PetDayDecision decide(PetDayInput in) {
        List<SendGroup> send = new ArrayList<>();
        List<Cancel> cancels = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        LocalDate day = in.day();
        if (in.pet().status() == PetStatus.READ_ONLY) {
            in.todayRows().forEach(r -> cancels.add(new Cancel(r, CancelReason.READ_ONLY, null, null)));
            return new PetDayDecision(send, cancels, moves);
        }
        List<HealthReminder> remaining = new ArrayList<>();
        Set<LocalDate> taken = new HashSet<>(in.futurePendingDays());
        for (HealthReminder r : in.todayRows()) {
            ReminderKind k = r.getReminderKind();
            if (in.pregnantActive()) {
                if (k.family() == ReminderFamily.VACCINE || k == ReminderKind.PYOMETRA_WATCH
                        || k == ReminderKind.HEAT_DOG_D_MINUS_7) {
                    cancels.add(new Cancel(r, CancelReason.PAUSED, null, null));
                    continue;
                }
                if (k == ReminderKind.FIRST_VACCINE) {
                    moves.add(new Move(r, day.plusDays(1), false)); // 挂起：不计入顺延天数，不会 expired_defer
                    taken.add(day.plusDays(1)); // 次日已有它，别的顺延不再落到那天
                    continue;
                }
            }
            if (r.getOrigin() == ReminderOrigin.PROFILE) {
                Optional<Cancel> c = recheckProfile(r, in.context(), day);
                if (c.isPresent()) {
                    cancels.add(c.get());
                    continue;
                }
            }
            remaining.add(r);
        }

        List<List<HealthReminder>> groups = merge(remaining);
        List<List<HealthReminder>> capped = new ArrayList<>();
        for (List<HealthReminder> g : groups) {
            if (tier(g) <= 2) {
                send.add(group(in, g)); // 档 1–2 不占名额、不受生日 / 纪念日影响
            } else {
                capped.add(g);
            }
        }
        capped.sort(PRIORITY);
        if (in.scheduledPushToday()) {
            for (List<HealthReminder> g : capped) {
                if (movable(g)) {
                    defer(in, g, SCHEDULED_PUSH, taken, cancels, moves);
                } else {
                    g.forEach(r -> cancels.add(new Cancel(r, CancelReason.SKIPPED_SCHEDULED_PUSH, SCHEDULED_PUSH,
                            null)));
                }
            }
            return new PetDayDecision(send, cancels, moves);
        }
        List<List<HealthReminder>> contenders = new ArrayList<>();
        for (List<HealthReminder> g : capped) {
            if (in.recallToday() && tier(g) == 8) {
                defer(in, g, SCHEDULED_PUSH, taken, cancels, moves); // 召回日只让档 8 让路
            } else {
                contenders.add(g);
            }
        }
        if (!contenders.isEmpty()) {
            List<HealthReminder> winner = contenders.get(0);
            send.add(group(in, winner));
            String winnerKind = winner.get(0).getReminderKind().name();
            for (List<HealthReminder> loser : contenders.subList(1, contenders.size())) {
                if (movable(loser)) {
                    defer(in, loser, winnerKind, taken, cancels, moves);
                } else {
                    loser.forEach(r -> cancels.add(new Cancel(r, CancelReason.SKIPPED_CAP, winnerKind, null)));
                }
            }
        }
        return new PetDayDecision(send, cancels, moves);
    }

    // ===== 合并与排序 =====

    /** 同类同推送点合并（疫苗 / 驱虫 / Vet visit / Custom）；其余每条单独一组。保持输入顺序。 */
    static List<List<HealthReminder>> merge(List<HealthReminder> rows) {
        Map<String, List<HealthReminder>> out = new LinkedHashMap<>();
        for (HealthReminder r : rows) {
            ReminderFamily f = r.getReminderKind().family();
            String key = f.mergeable() ? f.name() + "|" + r.getPushPoint().name() : "row|" + r.getId() + "|"
                    + System.identityHashCode(r);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        return new ArrayList<>(out.values());
    }

    private static final Comparator<List<HealthReminder>> PRIORITY = Comparator
            .comparingInt(HealthReminderPlanner::tier)
            .thenComparing(g -> movable(g)) // 不可移动者胜（false 在前）
            .thenComparing(HealthReminderPlanner::refDate)
            .thenComparingInt(g -> g.stream().mapToInt(r -> r.getReminderKind().catalogueOrder()).min().orElse(0));

    static int tier(List<HealthReminder> g) {
        return g.stream().mapToInt(HealthReminder::getTier).min().orElse(8);
    }

    static boolean movable(List<HealthReminder> g) {
        return g.stream().allMatch(HealthReminder::isMovable);
    }

    /** 同档比较用的日期：next_date_ref（无则原定日），取组内最早。 */
    static LocalDate refDate(List<HealthReminder> g) {
        return g.stream().map(r -> r.getNextDateRef() != null ? r.getNextDateRef() : r.getDueDate())
                .min(LocalDate::compareTo).orElse(LocalDate.MAX);
    }

    private static SendGroup group(PetDayInput in, List<HealthReminder> rows) {
        PushPoint point = rows.get(0).getPushPoint();
        return new SendGroup(in.pet().petId(), in.pet().ownerId(), in.day(), point, List.copyOf(rows));
    }

    // ===== 顺延 =====

    private static void defer(PetDayInput in, List<HealthReminder> g, String displacedBy, Set<LocalDate> taken,
            List<Cancel> cancels, List<Move> moves) {
        boolean tier8 = tier(g) == 8;
        LocalDate earliestDue = g.stream().map(HealthReminder::getDueDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate limit = earliestDue.plusDays(MAX_DEFER_DAYS);
        LocalDate target = null;
        for (LocalDate x = in.day().plusDays(1); !x.isAfter(limit); x = x.plusDays(1)) {
            if (taken.contains(x) || in.futureScheduledPushDays().contains(x)
                    || (tier8 && in.futureRecallDays().contains(x))) {
                continue;
            }
            target = x;
            break;
        }
        for (HealthReminder r : g) {
            if (target == null || ChronoUnit.DAYS.between(r.getDueDate(), target) > MAX_DEFER_DAYS) {
                cancels.add(new Cancel(r, CancelReason.EXPIRED_DEFER, displacedBy, null));
            } else {
                moves.add(new Move(r, target, true));
            }
        }
        if (target != null) {
            taken.add(target);
        }
    }

    // ===== 档案类发送日再校验 =====

    static Optional<Cancel> recheckProfile(HealthReminder r, PetReminderContext ctx, LocalDate day) {
        if (ctx == null) {
            return Optional.empty();
        }
        PetHealthSnapshot pet = ctx.pet();
        switch (r.getReminderKind()) {
            case FIRST_VACCINE -> {
                Optional<Long> rec = latestOf(ctx, HealthRecordType.VACCINE);
                if (rec.isPresent()) {
                    return Optional.of(new Cancel(r, CancelReason.SATISFIED, null, rec.get()));
                }
                if (pet.birthday() == null || !day.isBefore(pet.birthday().plusMonths(HealthDates.YOUNG_MONTHS))) {
                    return Optional.of(new Cancel(r, CancelReason.REBUILT, null, null));
                }
            }
            case FIRST_DEWORM -> {
                Optional<Long> rec = latestOf(ctx, HealthRecordType.DEWORM);
                if (rec.isPresent()) {
                    return Optional.of(new Cancel(r, CancelReason.SATISFIED, null, rec.get()));
                }
            }
            case SPAY_NEUTER_SUGGESTION -> {
                Optional<Long> rec = latestOf(ctx, HealthRecordType.NEUTER);
                if (rec.isPresent()) {
                    return Optional.of(new Cancel(r, CancelReason.SATISFIED, null, rec.get()));
                }
                if (pet.isNeutered() || pet.sex() == null || pet.birthday() == null
                        || !day.isBefore(pet.birthday().plusMonths(12))) {
                    return Optional.of(new Cancel(r, CancelReason.REBUILT, null, null));
                }
            }
            case CHECKUP -> {
                CheckupDueRule.CheckupDue due = ctx.checkupDue();
                if (due != null && due.dueDate().isAfter(day)) {
                    // 区间内有到访：到期日已后移（由 Vet visit 记录或兽医问诊引起）。
                    return Optional.of(new Cancel(r, CancelReason.SATISFIED, null,
                            latestOf(ctx, HealthRecordType.VET_VISIT).orElse(null)));
                }
            }
            default -> {
            }
        }
        return Optional.empty();
    }

    private static Optional<Long> latestOf(PetReminderContext ctx, HealthRecordType type) {
        return ctx.records().stream().filter(x -> x.type() == type)
                .max(HealthRecordSnapshot::compareChronologically).map(HealthRecordSnapshot::id);
    }
}

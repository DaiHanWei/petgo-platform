package com.tailtopia.notify.schedule;

import com.tailtopia.profile.dto.PetProfileSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 陪伴纪念日计划规则（V1.5.0 onboarding-recall Story 5.2 · FR-182 · OR-16 / OR-20）—— <b>纯函数</b>，
 * 19:00 WIB 日扫（{@link ScheduledPushPlanner#planAnniversaries}）与健康提醒占位
 * （{@code RetentionPushCalendar.anniversaryPushPets}）<b>共用同一份</b>，保证两边算出的「当天会推的宠物」一致。
 *
 * <ul>
 *   <li>候选 = 当天（WIB）满 {@link ScheduledPushPlanner#ANNIVERSARY_NODES} 天的宠物：天数 = {@code wibDate − createdDateWib}；</li>
 *   <li>只读宠物、主人非 {@code isActive} → 剔除（不推、不写通知、不写去重标记）；</li>
 *   <li>同一主人当天多只 → 建档最早（{@code created_at, id} 升序）的那只 {@code push=true}，其余 {@code push=false}
 *       （只写通知中心）。</li>
 * </ul>
 * 输出顺序同入参顺序。
 */
public final class AnniversaryPlan {

    private AnniversaryPlan() {
    }

    /** 一条纪念日计划：{@code push=false} 只写通知中心。 */
    public record AnniversaryPush(long petId, long ownerId, String petName, int days, boolean push) {
    }

    /** 同主人取最早：{@code created_at}（缺失按 WIB 建档日）→ {@code id}。 */
    private static final Comparator<PetProfileSnapshot> EARLIEST = Comparator
            .comparing(PetProfileSnapshot::createdAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .thenComparing(PetProfileSnapshot::createdDateWib, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparingLong(PetProfileSnapshot::id);

    public static List<AnniversaryPush> compute(List<PetProfileSnapshot> snapshots, LocalDate wibDate) {
        List<PetProfileSnapshot> due = new ArrayList<>();
        Map<Long, Integer> daysOf = new HashMap<>();
        for (PetProfileSnapshot p : snapshots) {
            Integer days = nodeDays(p, wibDate);
            if (days == null || !p.isActivePet() || !p.ownerActive()) {
                continue;
            }
            due.add(p);
            daysOf.put(p.id(), days);
        }
        Map<Long, PetProfileSnapshot> earliestByOwner = new HashMap<>();
        for (PetProfileSnapshot p : due) {
            earliestByOwner.merge(p.ownerId(), p, (a, b) -> EARLIEST.compare(a, b) <= 0 ? a : b);
        }
        List<AnniversaryPush> out = new ArrayList<>(due.size());
        for (PetProfileSnapshot p : due) {
            boolean push = earliestByOwner.get(p.ownerId()).id() == p.id();
            out.add(new AnniversaryPush(p.id(), p.ownerId(), p.name(), daysOf.get(p.id()), push));
        }
        return out;
    }

    /** 当天恰为节点 → 天数；否则 null。建档日（WIB）缺失 → null。 */
    static Integer nodeDays(PetProfileSnapshot p, LocalDate wibDate) {
        if (p.createdDateWib() == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(p.createdDateWib(), wibDate);
        if (days < 0 || days > Integer.MAX_VALUE) {
            return null;
        }
        return ScheduledPushPlanner.ANNIVERSARY_NODES.contains((int) days) ? (int) days : null;
    }
}

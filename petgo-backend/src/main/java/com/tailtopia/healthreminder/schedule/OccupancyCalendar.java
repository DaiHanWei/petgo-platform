package com.tailtopia.healthreminder.schedule;

import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.lifecycle.RetentionPushCalendar;
import com.tailtopia.notify.schedule.PlannedPush;
import com.tailtopia.notify.schedule.ScheduledPushPlanner;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetProfileSnapshot;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⑤ 占用（V1.5.0 Story 2.3 · AC3，PRD FR-166 Birthday / Other automated pushes，架构 Z-12）：生日 / 第一个生日、纪念日、
 * 召回日，一次批量取当天及之后 3 天（顺延窗口）。🔴 地基 / ③ 所有的类只调用：{@code ScheduledPushPlanner.plan(...)} 判
 * 生日与第一个生日（类型 {@code PET_BIRTHDAY} / {@code MILESTONE_NODE}）、{@code RetentionPushCalendar} 判纪念日与召回日。
 * ③ 若改了 {@code plan} 的签名或拆了类，合并时只改这里一处调用。
 */
@Component
public class OccupancyCalendar {

    static final int WINDOW_DAYS = 3;

    private final ScheduledPushPlanner scheduledPushPlanner;
    private final RetentionPushCalendar retentionCalendar;
    private final PetProfileRepository pets;

    public OccupancyCalendar(ScheduledPushPlanner scheduledPushPlanner, RetentionPushCalendar retentionCalendar,
            PetProfileRepository pets) {
        this.scheduledPushPlanner = scheduledPushPlanner;
        this.retentionCalendar = retentionCalendar;
        this.pets = pets;
    }

    /** 当天起 4 天的占用。 */
    public record Calendar(LocalDate day, Map<Long, Set<LocalDate>> scheduledByPet,
            Map<Long, Set<LocalDate>> recallByUser, Map<Long, Long> ownerByPet) {

        public PetDayProcessor.Occupancy occupancyFor(long petId, long ownerId) {
            Set<LocalDate> sched = scheduledByPet.getOrDefault(petId, Set.of());
            Set<LocalDate> recall = recallByUser.getOrDefault(ownerId, Set.of());
            return new PetDayProcessor.Occupancy(sched.contains(day), recall.contains(day), future(sched),
                    future(recall));
        }

        /** 该用户今天有没有生日 / 纪念日（任一宠物）或召回日。 */
        public boolean userBusyToday(long userId) {
            return busyOwnersToday().contains(userId);
        }

        /** 今天有占用的主人（一次算好）。 */
        public Set<Long> busyOwnersToday() {
            Set<Long> out = new HashSet<>();
            recallByUser.forEach((u, days) -> {
                if (days.contains(day)) {
                    out.add(u);
                }
            });
            scheduledByPet.forEach((pet, days) -> {
                if (days.contains(day) && ownerByPet.containsKey(pet)) {
                    out.add(ownerByPet.get(pet));
                }
            });
            return out;
        }

        private Set<LocalDate> future(Set<LocalDate> days) {
            Set<LocalDate> out = new HashSet<>(days);
            out.remove(day);
            return out;
        }
    }

    @Transactional(readOnly = true)
    public Calendar load(Map<Long, Long> ownerByPet, LocalDate day) {
        Map<Long, Set<LocalDate>> scheduled = new HashMap<>();
        Map<Long, Set<LocalDate>> recall = new HashMap<>();
        if (ownerByPet.isEmpty()) {
            return new Calendar(day, scheduled, recall, ownerByPet);
        }
        List<PetProfileSnapshot> snaps = pets.findAllById(ownerByPet.keySet()).stream().map(OccupancyCalendar::snap)
                .toList();
        Set<Long> owners = new HashSet<>(ownerByPet.values());
        for (int i = 0; i <= WINDOW_DAYS; i++) {
            LocalDate d = day.plusDays(i);
            for (PlannedPush p : scheduledPushPlanner.plan(d, snaps, Set.of())) {
                if (p.type() == NotificationType.PET_BIRTHDAY || p.type() == NotificationType.MILESTONE_NODE) {
                    scheduled.computeIfAbsent(p.petProfileId(), k -> new HashSet<>()).add(d);
                }
            }
            for (Long petId : retentionCalendar.anniversaryPushPets(ownerByPet.keySet(), d)) {
                scheduled.computeIfAbsent(petId, k -> new HashSet<>()).add(d);
            }
            for (Long userId : retentionCalendar.recallNodeUsers(owners, d)) {
                recall.computeIfAbsent(userId, k -> new HashSet<>()).add(d);
            }
        }
        return new Calendar(day, scheduled, recall, ownerByPet);
    }

    /** 与 {@code PetProfileQueryService.allSnapshots} 同口径（建档日期按 UTC 折算，纪念日判定所用）。 */
    private static PetProfileSnapshot snap(PetProfile p) {
        return new PetProfileSnapshot(p.getId(), p.getOwnerId(), p.getName(), p.getBirthday(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate());
    }
}

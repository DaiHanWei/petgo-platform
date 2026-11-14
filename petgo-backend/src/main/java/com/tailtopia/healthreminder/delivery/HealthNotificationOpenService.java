package com.tailtopia.healthreminder.delivery;

import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.repository.HealthNotificationRepository;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.SatisfactionLookup;
import com.tailtopia.healthreminder.web.HealthNotificationOpenResponse;
import com.tailtopia.notify.service.NotificationCenterService;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PregnancyFlag;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点开健康提醒（V1.5.0 Story 2.4 · AC5，PRD FR-167、AC12 / AC46 ~ AC49，架构增量 §5.4）。token 不存在或不属于本人 → 404
 * （不区分）；成功时把对应通知中心条目标已读。
 *
 * <p>判定顺序：宠物已删 / 只读 / 非本人 → {@code PET_UNAVAILABLE}；承载的提醒全部已满足 → {@code PAGE_SATISFIED}（聚焦
 * 满足它的最新记录）；驱虫安全情形（<b>按点开时的当前档案判</b>，怀孕在前）→ {@code PAGE + safetyBoxes}；否则按发出时的 route。
 */
@Service
public class HealthNotificationOpenService {

    private final HealthNotificationRepository notifications;
    private final HealthReminderRepository reminders;
    private final PetProfileRepository pets;
    private final HealthRecordRepository records;
    private final NotificationCenterService notificationCenter;

    public HealthNotificationOpenService(HealthNotificationRepository notifications,
            HealthReminderRepository reminders, PetProfileRepository pets, HealthRecordRepository records,
            NotificationCenterService notificationCenter) {
        this.notifications = notifications;
        this.reminders = reminders;
        this.pets = pets;
        this.records = records;
        this.notificationCenter = notificationCenter;
    }

    @Transactional
    public HealthNotificationOpenResponse open(long userId, String token) {
        HealthNotification n = notifications.findByPublicToken(token)
                .filter(x -> x.getUserId() == userId)
                .orElseThrow(() -> AppException.notFound("通知不存在"));
        if (n.getInboxNotificationId() != null) {
            notificationCenter.markReadById(userId, n.getInboxNotificationId());
        }
        HealthNotificationOpenResponse.Analytics analytics = new HealthNotificationOpenResponse.Analytics(
                n.getPublicToken(), n.getNotificationKind().name(), n.getPushPoint().wire(), n.getOrigin().wire(),
                n.getMergedCount());
        Optional<PetProfile> pet = pets.findById(n.getPetProfileId())
                .filter(p -> p.getOwnerId() == userId && p.getStatus() != PetStatus.READ_ONLY);
        if (pet.isEmpty()) {
            return new HealthNotificationOpenResponse("PET_UNAVAILABLE", null, null, null, null, null, analytics);
        }
        PetProfile p = pet.get();
        List<HealthReminder> rows = reminders.findByHealthNotificationId(n.getId());
        List<HealthRecordSnapshot> recs = records.findByPetProfileIdOrderByEventDateDescIdDesc(p.getId()).stream()
                .map(HealthRecordSnapshot::of).toList();
        boolean merged = n.getMergedCount() > 1;

        Optional<Long> satisfiedBy = allSatisfied(rows, recs, p.getPetType(), n);
        if (satisfiedBy.isPresent()) {
            return new HealthNotificationOpenResponse("OK", p.getId(), "PAGE_SATISFIED", satisfiedBy.get(), null,
                    null, analytics);
        }
        List<String> boxes = safetyBoxes(rows, p);
        Long focus = merged || rows.isEmpty() ? null : rows.get(0).getSourceRecordId();
        if (!boxes.isEmpty()) {
            return new HealthNotificationOpenResponse("OK", p.getId(), "PAGE", focus, null, boxes, analytics);
        }
        if (n.getRoute() == HealthNotification.Route.FORM && !rows.isEmpty()) {
            return new HealthNotificationOpenResponse("OK", p.getId(), "FORM", null, prefill(rows.get(0)), null,
                    analytics);
        }
        return new HealthNotificationOpenResponse("OK", p.getId(), "PAGE", focus, null, null, analytics);
    }

    /**
     * 全部提醒都已满足 → 满足它们的最新记录 id；否则空。记录类：发出后标记的 {@code satisfied_at}，或来源组现在有更新 / 覆盖
     * 记录；建议类：首针 / 首驱虫 / 绝育 → 有对应类型记录，体检 → 发出当天或之后有 Vet visit 记录。事件类不算满足。
     */
    static Optional<Long> allSatisfied(List<HealthReminder> rows, List<HealthRecordSnapshot> recs, PetType petType,
            HealthNotification n) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SatisfactionLookup lookup = SatisfactionLookup.of(recs, petType);
        Long best = null;
        HealthRecordSnapshot bestRec = null;
        for (HealthReminder r : rows) {
            Optional<Long> by = satisfiedBy(r, lookup, recs, n);
            if (by.isEmpty()) {
                return Optional.empty();
            }
            HealthRecordSnapshot rec = recs.stream().filter(x -> x.id() == by.get()).findFirst().orElse(null);
            if (bestRec == null || (rec != null && HealthRecordSnapshot.compareChronologically(rec, bestRec) > 0)) {
                best = by.get();
                bestRec = rec;
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Long> satisfiedBy(HealthReminder r, SatisfactionLookup lookup,
            List<HealthRecordSnapshot> recs, HealthNotification n) {
        if (r.getSatisfiedByRecordId() != null && lookup.exists(r.getSatisfiedByRecordId())) {
            return Optional.of(r.getSatisfiedByRecordId());
        }
        ReminderKind k = r.getReminderKind();
        if (r.getOrigin() == ReminderOrigin.RECORD || r.getOrigin() == ReminderOrigin.RESUME_CATCH_UP) {
            return lookup.satisfiedBy(r.getSourceRecordId());
        }
        HealthRecordType need = switch (k) {
            case FIRST_VACCINE -> HealthRecordType.VACCINE;
            case FIRST_DEWORM -> HealthRecordType.DEWORM;
            case SPAY_NEUTER_SUGGESTION -> HealthRecordType.NEUTER;
            case CHECKUP -> HealthRecordType.VET_VISIT;
            default -> null;
        };
        if (need == null) {
            return Optional.empty();
        }
        return recs.stream().filter(x -> x.type() == need)
                .filter(x -> need != HealthRecordType.VET_VISIT || !x.eventDate().isBefore(n.getSendDate()))
                .max(HealthRecordSnapshot::compareChronologically).map(HealthRecordSnapshot::id);
    }

    /**
     * 驱虫安全框（PRD FR-160 / FR-167，AC12）：任何驱虫类提醒（含首驱虫、Legacy、含驱虫的合并）且 ① 当前怀孕 → PREGNANT；
     * ② 狗 + 家里有猫 + 类型为 External / Flea+worm / 未知（Legacy、首驱虫）或合并含其一 → PERMETHRIN。怀孕在前。
     */
    static List<String> safetyBoxes(List<HealthReminder> rows, PetProfile pet) {
        List<String> out = new ArrayList<>();
        if (rows.stream().noneMatch(r -> r.getReminderKind().dewormRelated())) {
            return out;
        }
        if (PregnancyFlag.isActiveOn(pet, HealthDates.today())) {
            out.add("PREGNANT");
        }
        boolean riskyType = rows.stream().filter(r -> r.getReminderKind().dewormRelated())
                .anyMatch(r -> r.getDewormType() == null || r.getDewormType() == DewormType.EXTERNAL
                        || r.getDewormType() == DewormType.FLEA_WORM);
        if (pet.getPetType() == PetType.DOG && Boolean.TRUE.equals(pet.getCatInHome()) && riskyType) {
            out.add("PERMETHRIN");
        }
        return out;
    }

    /** 表单预填（FR-167 / 附录 C Route）。 */
    static HealthNotificationOpenResponse.Prefill prefill(HealthReminder r) {
        return switch (r.getReminderKind()) {
            case VACCINE_D_DAY, VACCINE_D_PLUS_7, VACCINE_D_MINUS_7 -> {
                VaccinePreset preset = r.getDueVaccinePreset();
                String name = preset != null && preset.label() != null ? preset.label() : r.getDueVaccineName();
                yield new HealthNotificationOpenResponse.Prefill("VACCINE", preset == null ? null : preset.name(), name,
                        r.getDueDose(), null, null, false);
            }
            case DEWORM_LEGACY_D_DAY, DEWORM_LEGACY_D_PLUS_3 ->
                    new HealthNotificationOpenResponse.Prefill("DEWORM", null, null, null, null, null, false);
            case DEWORM_INTERNAL_D_DAY, DEWORM_EXTERNAL_D_DAY, DEWORM_FLEA_WORM_D_DAY, DEWORM_HEARTWORM_D_DAY,
                    DEWORM_INTERNAL_D_PLUS_3, DEWORM_EXTERNAL_D_PLUS_3 ->
                    new HealthNotificationOpenResponse.Prefill("DEWORM", null, null, null,
                            r.getDewormType() == null ? null : r.getDewormType().name(), null, false);
            case VET_VISIT_D_DAY, VET_VISIT_D_MINUS_1 ->
                    new HealthNotificationOpenResponse.Prefill("VET_VISIT", null, null, null, null, null, false);
            case CUSTOM_D_DAY ->
                    new HealthNotificationOpenResponse.Prefill("CUSTOM", null, null, null, null, r.getCustomName(), false);
            case FIRST_VACCINE ->
                    new HealthNotificationOpenResponse.Prefill("VACCINE", null, null, null, null, null, true);
            case FIRST_DEWORM ->
                    new HealthNotificationOpenResponse.Prefill("DEWORM", null, null, null, null, null, true);
            case SPAY_NEUTER_SUGGESTION ->
                    new HealthNotificationOpenResponse.Prefill("NEUTER", null, null, null, null, null, true);
            case CHECKUP -> new HealthNotificationOpenResponse.Prefill("VET_VISIT", null, null, null, null, null, true);
            default -> null;
        };
    }
}

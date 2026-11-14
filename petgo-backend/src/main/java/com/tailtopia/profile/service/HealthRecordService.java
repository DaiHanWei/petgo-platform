package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.dto.HealthListItemResponse;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.dto.HealthRecordResponse;
import com.tailtopia.profile.dto.HealthRecordUpdateRequest;
import com.tailtopia.profile.event.HealthRecordChangedEvent;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import com.tailtopia.profile.event.PetHealthProfileChangedEvent;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordDefaults;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.LaunchDateProvider;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.RecordDisplayStatus;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结构化健康记录 CRUD（Story 7.1，FR-45/45A）。owner 一律取自 JWT；记录归属当前用户宠物档案，
 * 越权 → 404（防枚举，非 403）。健康数据=PII：日志只记 id + 动作，绝不落 type/name/note/date 明文。
 */
@Service
public class HealthRecordService {

    private final HealthRecordRepository records;
    private final HealthEventRepository healthEvents;
    private final PetResolver petResolver;
    private final ApplicationEventPublisher events;
    /** V1.4.0 multi-pet Story 2.1（AD-8）：只读宠物不可增 / 改 / 删健康记录（409 {@code pet-read-only}）。 */
    private final PetWriteGuard petWriteGuard;
    /** V1.5.0 Story 1.1：保存 NEUTER 记录时把宠物置 NEUTERED。 */
    private final PetProfileRepository profiles;
    /** V1.5.0 Story 1.2：回填静默的「有效上线日」（2.1 起读 health_reminder_settings）。 */
    private final LaunchDateProvider launchDates;

    private static final String LEGACY_VET_VISIT_MESSAGE = "当前 App 版本不支持 Kontrol dokter 记录，请升级到最新版本";

    public HealthRecordService(HealthRecordRepository records, HealthEventRepository healthEvents,
            PetResolver petResolver, ApplicationEventPublisher events, PetWriteGuard petWriteGuard,
            PetProfileRepository profiles, LaunchDateProvider launchDates) {
        this.profiles = profiles;
        this.launchDates = launchDates;
        this.petWriteGuard = petWriteGuard;
        this.records = records;
        this.healthEvents = healthEvents;
        this.petResolver = petResolver;
        this.events = events;
    }

    /**
     * 健康时间线混排（Story 7.2 · AC1）：结构化记录（editable）+ 问诊存档 ARCHIVED（只读）按 event_date 倒序。
     * 问诊条目**不入 health_records 表**——运行时合并。同日 RECORD 优先（稳定）。无档案 → 404。
     */
    @Transactional(readOnly = true)
    public List<HealthListItemResponse> timeline(long ownerId) {
        return timeline(ownerId, null);
    }

    /** V1.4.0 multi-pet：指定宠物（null = 默认宠物）的健康时间线。无客户端版本信息 → 按旧版（过滤 VET_VISIT）。 */
    @Transactional(readOnly = true)
    public List<HealthListItemResponse> timeline(long ownerId, Long petIdOrNull) {
        return timeline(ownerId, petIdOrNull, false);
    }

    /**
     * V1.5.0（Story 1.1 · AC4.5）：{@code v150Client=false}（旧版 App）时过滤掉 {@code VET_VISIT}——旧版不认识该类型。
     */
    @Transactional(readOnly = true)
    public List<HealthListItemResponse> timeline(long ownerId, Long petIdOrNull, boolean v150Client) {
        PetProfile pet = petResolver.resolve(ownerId, petIdOrNull);
        long petId = pet.getId();
        List<HealthRecord> petRecords = records.findByPetProfileIdOrderByEventDateDescIdDesc(petId);
        // V1.5.0 Story 1.2：显示状态在服务端按全部记录算（含旧版看不到的 VET_VISIT——它不参与其他组，不影响结果）。
        LocalDate today = HealthDates.today();
        Map<Long, RecordDisplayStatus.Display> display = RecordDisplayStatus.compute(
                petRecords.stream().map(HealthRecordSnapshot::of).toList(), PetHealthSnapshot.of(pet), today,
                launchDates.effectiveLaunchDate(today));
        List<HealthListItemResponse> merged = new ArrayList<>();
        for (HealthRecord r : petRecords) {
            if (!v150Client && r.getType() == HealthRecordType.VET_VISIT) {
                continue;
            }
            merged.add(HealthListItemResponse.ofRecord(r, display.get(r.getId())));
        }
        for (HealthEvent e : healthEvents.findByPetId(petId)) {
            if (e.getArchiveDecision() == ArchiveDecision.ARCHIVED) {
                merged.add(HealthListItemResponse.ofConsult(e));
            }
        }
        // event_date 倒序；同日结构化（editable）优先，其次问诊。
        merged.sort(Comparator
                .comparing(HealthListItemResponse::eventDate, Comparator.nullsLast(LocalDate::compareTo))
                .reversed()
                .thenComparing(i -> i.editable() ? 0 : 1));
        return merged;
    }

    @Transactional
    public HealthRecordResponse create(long ownerId, HealthRecordCreateRequest req) {
        return create(ownerId, null, req);
    }

    /** 无客户端版本信息 → 按旧版处理（新字段不读、来源 LEGACY_APP）。 */
    @Transactional
    public HealthRecordResponse create(long ownerId, Long petIdOrNull, HealthRecordCreateRequest req) {
        return create(ownerId, petIdOrNull, req, false);
    }

    /**
     * 新建（V1.5.0 Story 1.1 · AC4）：{@code v150Client} 由控制器按 {@code X-App-Version} 判定
     * （{@link ClientAppVersion#isV150OrLater}）。旧版：新字段一律不读、{@code record_origin=LEGACY_APP}、
     * {@code VET_VISIT} → 400；1.5.0：校验并写入新字段、{@code record_origin=APP}。
     */
    @Transactional
    public HealthRecordResponse create(long ownerId, Long petIdOrNull, HealthRecordCreateRequest req,
            boolean v150Client) {
        PetProfile pet = requireWritablePet(ownerId, petIdOrNull);
        long petId = pet.getId();
        HealthRecordType type = parseType(req.type());
        if (!v150Client && type == HealthRecordType.VET_VISIT) {
            throw AppException.validation(LEGACY_VET_VISIT_MESSAGE);
        }
        String customName = blankToNull(req.customName());
        requireCustomName(type, customName);
        HealthRecord r = HealthRecord.create(
                petId, type, customName, blankToNull(req.vaccineName()), req.eventDate(),
                blankToNull(req.note()));
        if (v150Client) {
            r.setRecordOrigin(RecordOrigin.APP);
            applyV150Fields(pet, r, new V150Fields(req.vaccinePreset(), req.dose(), req.dewormType(),
                    req.productDurationMonths(), req.nextDate(), req.clearNextDate()), true,
                    req.vaccineName() != null);
        } else {
            r.setRecordOrigin(RecordOrigin.LEGACY_APP);
            HealthRecordDefaults.applyForLegacyClient(PetHealthSnapshot.of(pet), r, historyOf(petId),
                    HealthRecordDefaults.LegacyChange.ofCreate());
        }
        HealthRecord persisted = records.save(r);
        HealthRecordResponse saved = HealthRecordResponse.from(persisted);
        // 里程碑第四触发路径（FR-45C，Story 7.2）：VACCINE→M3 / DEWORM→M4（listener 提交后异步幂等完成）。
        events.publishEvent(new HealthRecordCreatedEvent(ownerId, type, petId));
        events.publishEvent(new HealthRecordChangedEvent(ownerId, petId, saved.id(),
                HealthRecordChangedEvent.Change.CREATED, true));
        if (type == HealthRecordType.NEUTER) {
            markNeutered(ownerId, pet);
        }
        return saved;
    }

    @Transactional
    public HealthRecordResponse update(long ownerId, long recordId, HealthRecordUpdateRequest req) {
        return update(ownerId, null, recordId, req);
    }

    /** 记录须属于所选宠物（{@code findByIdAndPetProfileId}），用 A 的 petId 改 B 的记录 → 404。无版本信息 → 旧版。 */
    @Transactional
    public HealthRecordResponse update(long ownerId, Long petIdOrNull, long recordId, HealthRecordUpdateRequest req) {
        return update(ownerId, petIdOrNull, recordId, req, false);
    }

    /**
     * 编辑（V1.5.0 Story 1.1 · AC4）：旧版客户端新字段保留原值、不得改成 {@code VET_VISIT}；1.5.0 客户端校验并写新字段，
     * 编辑一条 {@code BACKFILL} 记录且给了 {@code nextDate} / {@code clearNextDate=true} → 来源改为 {@code APP}。
     */
    @Transactional
    public HealthRecordResponse update(long ownerId, Long petIdOrNull, long recordId, HealthRecordUpdateRequest req,
            boolean v150Client) {
        PetProfile pet = requireWritablePet(ownerId, petIdOrNull);
        long petId = pet.getId();
        HealthRecord r = records.findByIdAndPetProfileId(recordId, petId)
                .orElseThrow(() -> AppException.notFound("健康记录不存在"));
        Snapshot before = Snapshot.of(r);
        if (req.type() != null) {
            HealthRecordType newType = parseType(req.type());
            if (!v150Client && newType == HealthRecordType.VET_VISIT && r.getType() != HealthRecordType.VET_VISIT) {
                throw AppException.validation(LEGACY_VET_VISIT_MESSAGE);
            }
            r.setType(newType);
        }
        if (req.customName() != null) {
            r.setCustomName(blankToNull(req.customName()));
        }
        if (req.vaccineName() != null) {
            r.setVaccineName(blankToNull(req.vaccineName()));
        }
        if (req.eventDate() != null) {
            r.setEventDate(req.eventDate());
        }
        if (req.note() != null) {
            r.setNote(blankToNull(req.note()));
        }
        requireCustomName(r.getType(), r.getCustomName());
        if (v150Client) {
            boolean datePinned = req.nextDate() != null || Boolean.TRUE.equals(req.clearNextDate());
            applyV150Fields(pet, r, new V150Fields(req.vaccinePreset(), req.dose(), req.dewormType(),
                    req.productDurationMonths(), req.nextDate(), req.clearNextDate()), false,
                    req.vaccineName() != null);
            // 用户亲手定过 Next date → 不再是「回填静默」的存量行。
            if (datePinned && r.getRecordOrigin() == RecordOrigin.BACKFILL) {
                r.setRecordOrigin(RecordOrigin.APP);
            }
        } else {
            if (before.type() != r.getType()) {
                // 旧版改了类型：新字段对同类型保留原值，但不留旧类型的残值（如改成驱虫后还挂着疫苗预设 / 剂次）。
                clearFieldsNotBelongingTo(r);
            }
            HealthRecordDefaults.LegacyChange change = new HealthRecordDefaults.LegacyChange(false,
                    !Objects.equals(before.vaccineName(), r.getVaccineName()),
                    !Objects.equals(before.eventDate(), r.getEventDate()));
            // 只有改名 / 改日期才可能重算（Story 1.3），其余编辑不读历史。
            List<HealthRecordSnapshot> others = change.vaccineNameChanged() || change.eventDateChanged()
                    ? historyOf(petId).stream().filter(h -> h.id() != recordId).toList() : List.of();
            HealthRecordDefaults.applyForLegacyClient(PetHealthSnapshot.of(pet), r, others, change);
        }
        HealthRecordResponse saved = HealthRecordResponse.from(records.save(r));
        Snapshot after = Snapshot.of(r);
        events.publishEvent(new HealthRecordChangedEvent(ownerId, petId, recordId,
                HealthRecordChangedEvent.Change.UPDATED, !before.equals(after)));
        if (before.type() != HealthRecordType.NEUTER && r.getType() == HealthRecordType.NEUTER) {
            markNeutered(ownerId, pet);
        }
        return saved;
    }

    @Transactional
    public void delete(long ownerId, long recordId) {
        delete(ownerId, null, recordId);
    }

    /** 删除。删 NEUTER 记录<b>不回退</b>宠物绝育状态（PRD AC16）。 */
    @Transactional
    public void delete(long ownerId, Long petIdOrNull, long recordId) {
        long petId = requireWritablePet(ownerId, petIdOrNull).getId();
        HealthRecord r = records.findByIdAndPetProfileId(recordId, petId)
                .orElseThrow(() -> AppException.notFound("健康记录不存在"));
        records.delete(r);
        events.publishEvent(new HealthRecordChangedEvent(ownerId, petId, recordId,
                HealthRecordChangedEvent.Change.DELETED, true));
    }

    private List<HealthRecordSnapshot> historyOf(long petId) {
        return records.findByPetProfileIdOrderByEventDateDescIdDesc(petId).stream()
                .map(HealthRecordSnapshot::of).toList();
    }

    /** 1.5.0 客户端随请求带上的新字段。 */
    private record V150Fields(String vaccinePreset, Integer dose, String dewormType, Integer productDurationMonths,
            LocalDate nextDate, Boolean clearNextDate) {
    }

    /**
     * 写 1.5.0 新字段（架构增量 §6.1）。新建：缺省 = 不设；编辑：缺省 = 不改（{@code nextDate} 另有 {@code clearNextDate}）。
     * 写完后按最终类型把不相干的字段清空（改类型时不留旧类型的残值）。
     */
    private static void applyV150Fields(PetProfile pet, HealthRecord r, V150Fields f, boolean creating,
            boolean nameGiven) {
        PetType species = pet.getPetType();
        boolean catOrDog = species == PetType.CAT || species == PetType.DOG;
        HealthRecordType type = r.getType();

        if (type == HealthRecordType.VACCINE) {
            if (f.vaccinePreset() != null) {
                // 非猫狗物种不存预设（只有自由文本）；猫狗须适用于该物种。
                VaccinePreset preset = presetForSpecies(
                        parseEnum(VaccinePreset.class, f.vaccinePreset(), "疫苗预设不合法"), species);
                VaccinePreset previous = r.getVaccinePreset();
                r.setVaccinePreset(preset);
                // 名字跟着预设走：本次没带名字、且原名为空或是旧预设的标签 → 换成新预设的标签。
                boolean nameIsOldLabel = r.getVaccineName() == null
                        || (previous != null && r.getVaccineName().equals(previous.label()));
                if (!nameGiven && nameIsOldLabel && preset != null && preset != VaccinePreset.OTHER) {
                    r.setVaccineName(preset.label());
                }
            } else if (!catOrDog) {
                r.setVaccinePreset(null);
            }
            if (f.dose() != null) {
                r.setDose(f.dose());
            }
            if (r.getVaccinePreset() == null || !r.getVaccinePreset().series()) {
                r.setDose(null); // 只有系列预设落剂次
            }
        }

        if (type == HealthRecordType.DEWORM) {
            if (f.dewormType() != null) {
                r.setDewormType(parseEnum(DewormType.class, f.dewormType(), "驱虫类型不合法"));
            }
            if (r.getDewormType() == null) {
                // 1.5.0 客户端的驱虫必须分型（含编辑一条存量未分型记录，哪怕只改备注）。
                throw AppException.validation("请选择驱虫类型");
            }
            requireHeartwormForDog(r.getDewormType(), species);
            if (r.getDewormType() == DewormType.EXTERNAL) {
                Integer months = f.productDurationMonths();
                if (months != null) {
                    requireValidDuration(months);
                    r.setProductDurationMonths(months);
                } else if (r.getProductDurationMonths() == null) {
                    r.setProductDurationMonths(1);
                }
            } else {
                r.setProductDurationMonths(null); // FLEA_WORM 恒 null（规则按 1 个月算）
            }
        }
        clearFieldsNotBelongingTo(r);

        if (type == HealthRecordType.VET_VISIT) {
            // Kontrol dokter：只有 eventDate / note / nextDate 有意义。
            r.setVaccineName(null);
            r.setCustomName(null);
        }

        if (Boolean.TRUE.equals(f.clearNextDate())) {
            r.setNextDate(null);
        } else if (f.nextDate() != null) {
            r.setNextDate(f.nextDate());
        } else if (creating) {
            r.setNextDate(null);
        }
        if (type == HealthRecordType.NEUTER) {
            r.setNextDate(null); // 绝育没有 Next date
        }
        // 按最终状态校验（含「只改了 eventDate、沿用旧 nextDate」的编辑）：eventDate < nextDate ≤ eventDate + 3 年。
        LocalDate next = r.getNextDate();
        if (next != null && (!next.isAfter(r.getEventDate()) || next.isAfter(r.getEventDate().plusYears(3)))) {
            throw AppException.validation("下次日期须晚于记录日期，且不超过 3 年");
        }
    }

    /** 产品时长只允许 1 / 3 / 8 个月（预填与保存同一口径）。 */
    static void requireValidDuration(Integer months) {
        if (months != null && months != 1 && months != 3 && months != 8) {
            throw AppException.validation("产品时长只能是 1 / 3 / 8 个月");
        }
    }

    /** 心丝虫只适用于狗（预填与保存同一口径）。 */
    static void requireHeartwormForDog(DewormType type, PetType species) {
        if (type == DewormType.HEARTWORM && species != PetType.DOG) {
            throw AppException.validation("心丝虫预防只适用于狗");
        }
    }

    /** 预设须适用于物种；非猫狗物种一律不存预设 → 返回 null（预填与保存同一口径）。 */
    static VaccinePreset presetForSpecies(VaccinePreset preset, PetType species) {
        if (preset == null || (species != PetType.CAT && species != PetType.DOG)) {
            return null;
        }
        if (!preset.appliesTo(species)) {
            throw AppException.validation("疫苗预设与宠物物种不符");
        }
        return preset;
    }

    /** 清掉不属于当前类型的新字段：预设 / 剂次只属于疫苗，驱虫类型 / 产品时长只属于驱虫。 */
    private static void clearFieldsNotBelongingTo(HealthRecord r) {
        if (r.getType() != HealthRecordType.VACCINE) {
            r.setVaccinePreset(null);
            r.setDose(null);
        }
        if (r.getType() != HealthRecordType.DEWORM) {
            r.setDewormType(null);
            r.setProductDurationMonths(null);
        }
    }

    /**
     * 保存 NEUTER 记录 → 同一事务把宠物置 NEUTERED（架构增量 §6.2，PRD AC16）；已是 NEUTERED 不重复写、不发事件。
     */
    private void markNeutered(long ownerId, PetProfile pet) {
        if (pet.getNeuterStatus() == NeuterStatus.NEUTERED) {
            return;
        }
        pet.setNeuterStatus(NeuterStatus.NEUTERED);
        profiles.save(pet);
        events.publishEvent(new PetHealthProfileChangedEvent(ownerId, pet.getId(),
                false, false, true, false, false, Instant.now()));
    }

    /** 提醒相关字段快照（除 note 外全部）：编辑前后不等 → {@code reminderRelevant=true}。 */
    private record Snapshot(HealthRecordType type, VaccinePreset vaccinePreset, String vaccineName, Integer dose,
            DewormType dewormType, Integer productDurationMonths, LocalDate eventDate, LocalDate nextDate,
            String customName) {

        static Snapshot of(HealthRecord r) {
            return new Snapshot(r.getType(), r.getVaccinePreset(), r.getVaccineName(), r.getDose(),
                    r.getDewormType(), r.getProductDurationMonths(), r.getEventDate(), r.getNextDate(),
                    r.getCustomName());
        }
    }

    /** 写路径：先归属（{@link PetResolver}，404）再可写（{@link PetWriteGuard}，只读 409）。 */
    private PetProfile requireWritablePet(long ownerId, Long petId) {
        PetProfile pet = petResolver.resolve(ownerId, petId);
        petWriteGuard.requireWritable(pet);
        return pet;
    }

    static HealthRecordType parseType(String raw) {
        if (raw == null) {
            throw AppException.validation("记录类型必选");
        }
        try {
            return HealthRecordType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("记录类型非法，须为 VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM/VET_VISIT 之一");
        }
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String message) {
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            throw AppException.validation(message);
        }
    }

    private static void requireCustomName(HealthRecordType type, String customName) {
        if (type == HealthRecordType.CUSTOM && (customName == null || customName.isBlank())) {
            throw AppException.validation("自定义记录须填写名称");
        }
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

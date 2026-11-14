package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.dto.HealthRecordPreviewRequest;
import com.tailtopia.profile.dto.HealthRecordPreviewResponse;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.NextDatePlan;
import com.tailtopia.profile.health.NextDateRules;
import com.tailtopia.profile.health.NextDateSuggestion;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.PreviewHint;
import com.tailtopia.profile.health.PreviewHints;
import com.tailtopia.profile.health.RecordDraft;
import com.tailtopia.profile.repository.HealthRecordRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 健康记录表单预填（V1.5.0 Story 1.2 · AC7，架构增量 §6.1）。只读，不落库、不发事件。规则全在 {@code profile.health}，
 * 本类只负责取宠物与历史快照（编辑时排除 {@code recordId} 自己）。🔒 日志禁记任何字段。
 */
@Service
public class HealthRecordPreviewService {

    private final HealthRecordRepository records;
    private final PetResolver petResolver;

    public HealthRecordPreviewService(HealthRecordRepository records, PetResolver petResolver) {
        this.records = records;
        this.petResolver = petResolver;
    }

    @Transactional(readOnly = true)
    public HealthRecordPreviewResponse preview(long ownerId, Long petIdOrNull, HealthRecordPreviewRequest req) {
        PetProfile pet = petResolver.resolve(ownerId, petIdOrNull);
        PetHealthSnapshot snap = PetHealthSnapshot.of(pet);
        List<HealthRecordSnapshot> history = records.findByPetProfileIdOrderByEventDateDescIdDesc(pet.getId())
                .stream().map(HealthRecordSnapshot::of).toList();
        HealthRecordSnapshot self = req.recordId() == null ? null : history.stream()
                .filter(r -> r.id() == req.recordId()).findFirst().orElse(null);
        // 解析与校验与保存同一口径（HealthRecordService），预填不会给出保存会拒的组合。
        HealthRecordType type = HealthRecordService.parseType(req.type());
        VaccinePreset preset = req.vaccinePreset() == null ? null : HealthRecordService.presetForSpecies(
                HealthRecordService.parseEnum(VaccinePreset.class, req.vaccinePreset(), "疫苗预设不合法"),
                pet.getPetType());
        DewormType deworm = req.dewormType() == null ? null
                : HealthRecordService.parseEnum(DewormType.class, req.dewormType(), "驱虫类型不合法");
        HealthRecordService.requireHeartwormForDog(deworm, pet.getPetType());
        HealthRecordService.requireValidDuration(req.productDurationMonths());
        // 编辑时没带时长 → 沿用已存值（保存也保留它）。
        Integer duration = req.productDurationMonths() != null || self == null
                ? req.productDurationMonths() : self.durationMonths();
        RecordDraft draft = new RecordDraft(req.recordId(), type, preset, req.vaccineName(), req.dose(), deworm,
                duration, req.eventDate(), self == null ? null : self.createdAt());

        NextDatePlan plan = NextDateRules.plan(snap, draft, history);
        NextDateSuggestion s = plan.suggestion();
        // 1.5.0 表单的驱虫必须分型：没选类型时不给 Legacy 的 +3 个月（那是旧版 / 回填专用）。
        if (type == HealthRecordType.DEWORM && deworm == null) {
            s = null;
        }
        LocalDate eventDate = req.eventDate();
        // 锁定（发情周期后续记录）时字段只读，不给可选范围。
        boolean editable = eventDate != null && plan.allowed() && !plan.locked();
        List<String> hints = PreviewHints.of(snap, draft, history).stream().map(PreviewHint::name).toList();
        return new HealthRecordPreviewResponse(
                plan.allowed() && !plan.locked() && s != null ? s.nextDate() : null,
                plan.allowed(),
                plan.locked(),
                plan.lockedNextDate(),
                editable ? eventDate.plusDays(1) : null,
                editable ? eventDate.plusYears(3) : null,
                s == null || s.dueVaccinePreset() == null ? null : s.dueVaccinePreset().name(),
                s == null ? null : s.dueDose(),
                hints);
    }
}

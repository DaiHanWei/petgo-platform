package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.HealthListItemResponse;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.dto.HealthRecordResponse;
import com.tailtopia.profile.dto.HealthRecordUpdateRequest;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private final PetProfileRepository profiles;
    private final ApplicationEventPublisher events;

    public HealthRecordService(HealthRecordRepository records, HealthEventRepository healthEvents,
            PetProfileRepository profiles, ApplicationEventPublisher events) {
        this.records = records;
        this.healthEvents = healthEvents;
        this.profiles = profiles;
        this.events = events;
    }

    /**
     * 健康时间线混排（Story 7.2 · AC1）：结构化记录（editable）+ 问诊存档 ARCHIVED（只读）按 event_date 倒序。
     * 问诊条目**不入 health_records 表**——运行时合并。同日 RECORD 优先（稳定）。无档案 → 404。
     */
    @Transactional(readOnly = true)
    public List<HealthListItemResponse> timeline(long ownerId) {
        long petId = requirePet(ownerId);
        List<HealthListItemResponse> merged = new ArrayList<>();
        for (HealthRecord r : records.findByPetProfileIdOrderByEventDateDescIdDesc(petId)) {
            merged.add(HealthListItemResponse.ofRecord(r));
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
        long petId = requirePet(ownerId);
        HealthRecordType type = parseType(req.type());
        String customName = blankToNull(req.customName());
        requireCustomName(type, customName);
        HealthRecord r = HealthRecord.create(
                petId, type, customName, blankToNull(req.vaccineName()), req.eventDate(),
                blankToNull(req.note()));
        HealthRecordResponse saved = HealthRecordResponse.from(records.save(r));
        // 里程碑第四触发路径（FR-45C，Story 7.2）：VACCINE→M3 / DEWORM→M4（listener 提交后异步幂等完成）。
        events.publishEvent(new HealthRecordCreatedEvent(ownerId, type));
        return saved;
    }

    @Transactional
    public HealthRecordResponse update(long ownerId, long recordId, HealthRecordUpdateRequest req) {
        long petId = requirePet(ownerId);
        HealthRecord r = records.findByIdAndPetProfileId(recordId, petId)
                .orElseThrow(() -> AppException.notFound("健康记录不存在"));
        if (req.type() != null) {
            r.setType(parseType(req.type()));
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
        return HealthRecordResponse.from(records.save(r));
    }

    @Transactional
    public void delete(long ownerId, long recordId) {
        long petId = requirePet(ownerId);
        HealthRecord r = records.findByIdAndPetProfileId(recordId, petId)
                .orElseThrow(() -> AppException.notFound("健康记录不存在"));
        records.delete(r);
    }

    private long requirePet(long ownerId) {
        return profiles.findByOwnerId(ownerId)
                .map(PetProfile::getId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }

    private static HealthRecordType parseType(String raw) {
        try {
            return HealthRecordType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("记录类型非法，须为 VACCINE/DEWORM/MENSTRUATION/NEUTER/CUSTOM 之一");
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

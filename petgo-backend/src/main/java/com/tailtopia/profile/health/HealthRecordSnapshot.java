package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 健康记录的不可变快照（V1.5.0 Story 1.2）：规则类一律「快照入、结果出」，不碰实体与仓储。2.x 提醒生成复用。
 * 🔒 含健康数据，禁止 toString 进日志。
 */
public record HealthRecordSnapshot(long id, HealthRecordType type, VaccinePreset vaccinePreset, String vaccineName,
        Integer dose, DewormType dewormType, Integer durationMonths, LocalDate eventDate, LocalDate nextDate,
        String customName, RecordOrigin origin, Instant createdAt) {

    public static HealthRecordSnapshot of(HealthRecord r) {
        return new HealthRecordSnapshot(r.getId() == null ? 0L : r.getId(), r.getType(), r.getVaccinePreset(),
                r.getVaccineName(), r.getDose(), r.getDewormType(), r.getProductDurationMonths(), r.getEventDate(),
                r.getNextDate(), r.getCustomName(), r.getRecordOrigin(),
                r.getCreatedAt() == null ? Instant.EPOCH : r.getCreatedAt());
    }

    /** 同组排序键：event date → created_at → id，均升序（「最新」= 最大）。 */
    public static int compareChronologically(HealthRecordSnapshot a, HealthRecordSnapshot b) {
        int c = a.eventDate().compareTo(b.eventDate());
        if (c != 0) {
            return c;
        }
        c = a.createdAt().compareTo(b.createdAt());
        return c != 0 ? c : Long.compare(a.id(), b.id());
    }
}

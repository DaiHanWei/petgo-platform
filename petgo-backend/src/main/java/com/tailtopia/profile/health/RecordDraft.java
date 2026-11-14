package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 待保存 / 待预填的一条记录（V1.5.0 Story 1.2）：新建时 {@code recordId=null}；编辑时带上原记录 id 与 created_at，
 * 历史计算会排除它自己。
 */
public record RecordDraft(Long recordId, HealthRecordType type, VaccinePreset vaccinePreset, String vaccineName,
        Integer dose, DewormType dewormType, Integer durationMonths, LocalDate eventDate, Instant createdAt) {

    /** 新建记录在周期 / 组内排序时的 id 占位（排在同日已有记录之后）。 */
    static final long NEW_RECORD_ID = Long.MAX_VALUE;

    /** 作为快照参与周期 / 覆盖组计算（不带 Next date）。 */
    HealthRecordSnapshot asSnapshot() {
        return new HealthRecordSnapshot(recordId == null ? NEW_RECORD_ID : recordId, type, vaccinePreset,
                vaccineName, dose, dewormType, durationMonths, eventDate, null, null, RecordOrigin.APP,
                createdAt == null ? Instant.MAX : createdAt);
    }
}

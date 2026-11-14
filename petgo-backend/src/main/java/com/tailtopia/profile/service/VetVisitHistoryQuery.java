package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.HealthDates;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.LaunchDateProvider;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.RecordDisplayStatus;
import com.tailtopia.profile.health.RecordStatus;
import com.tailtopia.profile.repository.HealthRecordRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「Konsultasi saya」里的 Kontrol dokter 条目（V1.5.0 Story 2.5，PRD FR-161，架构增量 §6.3）：profile 对 consult 暴露的
 * <b>只读端口</b>（consult 包不直接注入 profile 仓储）。来源 = 该账号全部宠物（宠物切换器里的，含只读）的 VET_VISIT 记录；
 * 显示状态复用 {@link RecordDisplayStatus}（规则只有一份）。🔒 备注属健康数据，只返回给本人，日志禁记。
 */
@Service
public class VetVisitHistoryQuery {

    /** 一条 Kontrol dokter。{@code displayNextDate} / {@code recordStatus} 同健康记录列表。 */
    public record VetVisitHistoryRow(long recordId, long petId, String petName, LocalDate eventDate, String note,
            LocalDate displayNextDate, RecordStatus recordStatus, Instant createdAt) {
    }

    private final PetResolver petResolver;
    private final HealthRecordRepository records;
    private final LaunchDateProvider launchDates;

    public VetVisitHistoryQuery(PetResolver petResolver, HealthRecordRepository records,
            LaunchDateProvider launchDates) {
        this.petResolver = petResolver;
        this.records = records;
        this.launchDates = launchDates;
    }

    @Transactional(readOnly = true)
    public List<VetVisitHistoryRow> forOwner(long userId) {
        LocalDate today = HealthDates.today();
        LocalDate launch = launchDates.effectiveLaunchDate(today);
        List<VetVisitHistoryRow> out = new ArrayList<>();
        for (PetProfile pet : petResolver.listOwned(userId)) {
            List<HealthRecord> petRecords = records.findByPetProfileIdOrderByEventDateDescIdDesc(pet.getId());
            if (petRecords.stream().noneMatch(r -> r.getType() == HealthRecordType.VET_VISIT)) {
                continue;
            }
            Map<Long, RecordDisplayStatus.Display> display = RecordDisplayStatus.compute(
                    petRecords.stream().map(HealthRecordSnapshot::of).toList(), PetHealthSnapshot.of(pet), today,
                    launch);
            for (HealthRecord r : petRecords) {
                if (r.getType() != HealthRecordType.VET_VISIT) {
                    continue;
                }
                RecordDisplayStatus.Display d = display.get(r.getId());
                out.add(new VetVisitHistoryRow(r.getId(), pet.getId(), pet.getName(), r.getEventDate(), r.getNote(),
                        d == null ? null : d.displayNextDate(), d == null ? RecordStatus.DONE : d.recordStatus(),
                        r.getCreatedAt()));
            }
        }
        return out;
    }
}

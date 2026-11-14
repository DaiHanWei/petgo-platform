package com.tailtopia.healthreminder.backfill;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V1.5.0 上线回填（Story 1.3 · AC1，PRD FR-168 Backfill，架构增量 §4.7「部署时」）：随应用启动跑一次，把存量（BACKFILL）
 * 健康记录补上预设、Next date，把有绝育记录的宠物置 NEUTERED。
 *
 * <p>按宠物分批（每批 200 只，一批一个事务）；决策全在 {@link BackfillPlanner}（纯函数）。<b>不走用户 API、不限流、
 * 不发任何事件</b>（不触发里程碑、不触发重建）。只写空字段 → 天然幂等，再跑一次无可改的行。日志只记批次计数。
 *
 * <p>测试配置置 {@code petgo.health-reminder.backfill-on-startup=false}，L1 测试显式调用 {@link #runBackfill()}。
 */
@Component
@ConditionalOnProperty(name = "petgo.health-reminder.backfill-on-startup", havingValue = "true",
        matchIfMissing = true)
public class HealthLaunchBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HealthLaunchBackfillRunner.class);
    static final int BATCH_PETS = 200;

    private final HealthRecordRepository records;
    private final PetProfileRepository profiles;
    private final TransactionTemplate tx;

    public HealthLaunchBackfillRunner(HealthRecordRepository records, PetProfileRepository profiles,
            TransactionTemplate tx) {
        this.records = records;
        this.profiles = profiles;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        runBackfill();
    }

    /** @return 改动的记录数 + 置 NEUTERED 的宠物数 */
    public int runBackfill() {
        return runBackfill(records.findPetIdsWithBackfillRecords());
    }

    /** 只回填给定宠物（L1 测试用，避免改写共享库里别的测试的数据）。 */
    public int runBackfill(List<Long> petIds) {
        int changed = 0;
        for (int from = 0; from < petIds.size(); from += BATCH_PETS) {
            List<Long> batch = petIds.subList(from, Math.min(from + BATCH_PETS, petIds.size()));
            Integer n = tx.execute(status -> backfillBatch(batch));
            changed += n == null ? 0 : n;
            log.info("health backfill batch pets={} changed={}", batch.size(), n);
        }
        log.info("health backfill done pets={} changed={}", petIds.size(), changed);
        return changed;
    }

    private int backfillBatch(List<Long> petIds) {
        Map<Long, List<HealthRecord>> byPet = records.findByPetProfileIdIn(petIds).stream()
                .collect(Collectors.groupingBy(HealthRecord::getPetProfileId));
        int changed = 0;
        for (PetProfile pet : profiles.findAllById(petIds)) {
            List<HealthRecord> petRecords = byPet.getOrDefault(pet.getId(), List.of());
            BackfillPlanner.Plan plan = BackfillPlanner.plan(PetHealthSnapshot.of(pet),
                    petRecords.stream().map(HealthRecordSnapshot::of).toList());
            if (plan.isEmpty()) {
                continue;
            }
            Map<Long, HealthRecord> byId = petRecords.stream()
                    .collect(Collectors.toMap(HealthRecord::getId, Function.identity()));
            for (BackfillPlanner.RecordUpdate u : plan.records()) {
                HealthRecord r = byId.get(u.recordId());
                if (u.setPreset() != null) {
                    r.setVaccinePreset(u.setPreset());
                }
                if (u.setNextDate() != null) {
                    r.setNextDate(u.setNextDate());
                }
                changed++;
            }
            if (plan.markNeutered()) {
                pet.setNeuterStatus(NeuterStatus.NEUTERED);
                changed++;
            }
        }
        return changed;
    }
}

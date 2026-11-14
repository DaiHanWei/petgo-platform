package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * &lt; 1.5.0 客户端的新手任务进度（V1.5.0 onboarding-recall Story 2.1 · OR-9）。
 *
 * <h2>为什么要分流</h2>
 * 旧包 {@code newbie_task_labels.dart} 只认这 6 个旧 key，收到新 key 会把大写 key 原样显示。
 * 所以旧版**照搬** V1.5.0 之前 {@code NewbieTaskService.progress} 的只读聚合（S1–S5 里程碑完成 + 健康记录存在性，
 * 每项任意一只宠物完成即算）与「无宠物 404」；只有 {@code lulusPemulaUnlocked} 与新版同口径（{@link LulusPemulaReader}）。
 * 新旧两套只在 {@code GET /api/v1/me/newbie-tasks} 这一个接口分流。
 */
@Component
public class LegacyNewbieTaskCompat {

    /** 旧 6 项（固定顺序；key 即对外标识）。不再依赖 {@code NewbieTask} 枚举。 */
    enum LegacyTask {
        CREATE_PROFILE("S1"),
        FIRST_PHOTO("S2"),
        SHARE_CARD("S3"),
        SAVE_CONSULT("S4"),
        FIRST_DAILY("S5"),
        /** 录入一条健康记录（取 {@code health_records} 存在性）。 */
        FIRST_HEALTH_RECORD(null);

        private final String milestoneSuffix;

        LegacyTask(String milestoneSuffix) {
            this.milestoneSuffix = milestoneSuffix;
        }

        boolean isHealthRecord() {
            return milestoneSuffix == null;
        }
    }

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final HealthRecordRepository healthRecords;
    private final LulusPemulaReader lulus;

    public LegacyNewbieTaskCompat(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, HealthRecordRepository healthRecords,
            LulusPemulaReader lulus) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.healthRecords = healthRecords;
        this.lulus = lulus;
    }

    /** 旧版 6 新手任务进度（无档案 → 404 防枚举，与 V1.5.0 之前逐字一致）。 */
    @Transactional(readOnly = true)
    public NewbieTaskResponse progress(long ownerId) {
        List<PetProfile> pets = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(ownerId);
        if (pets.isEmpty()) {
            throw AppException.notFound("尚未创建宠物档案");
        }

        List<NewbieTaskResponse.Item> items = new ArrayList<>();
        int done = 0;
        for (LegacyTask task : LegacyTask.values()) {
            boolean completed = pets.stream().anyMatch(pet -> task.isHealthRecord()
                    ? healthRecords.existsByPetProfileId(pet.getId())
                    : isMilestoneDone(pet.getId(), prefixOf(pet.getPetType()) + "-" + task.milestoneSuffix));
            if (completed) {
                done++;
            }
            items.add(new NewbieTaskResponse.Item(task.name(), completed));
        }
        return new NewbieTaskResponse(items, done, LegacyTask.values().length, lulus.unlocked(ownerId, pets));
    }

    private boolean isMilestoneDone(long petProfileId, String code) {
        return milestones.findByPetProfileIdAndCode(petProfileId, code)
                .map(m -> completions.existsByPetMilestoneId(m.getId()))
                .orElse(false);
    }

    /** pet_type → 清单 code 前缀（CAT→C / DOG→D / OTHER→G）。 */
    private static String prefixOf(PetType type) {
        return switch (type) {
            case CAT -> "C";
            case DOG -> "D";
            case OTHER -> "G";
        };
    }
}

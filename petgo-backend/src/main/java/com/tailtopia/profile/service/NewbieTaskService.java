package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.NewbieTask;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新手任务进度服务（Story 7.3 · FR-47）。归 profile 域。**只读聚合**——不物化、不写完成；
 * 任务完成态由既有里程碑完成（S1–S5）与 {@code health_records} 存在性推导，独立计数。
 *
 * <p>解锁副作用（6 任务全达成 → 完成 Lulus Pemula）由 {@link MilestoneCompletionService}
 * 承载（运行时事件触发），本服务不写。单宠假设：沿用 {@code findByOwnerId}（每 owner 单档案）。
 */
@Service
public class NewbieTaskService {

    private final PetProfileRepository profiles;
    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final HealthRecordRepository healthRecords;

    public NewbieTaskService(PetProfileRepository profiles, PetMilestoneRepository milestones,
            MilestoneCompletionRepository completions, HealthRecordRepository healthRecords) {
        this.profiles = profiles;
        this.milestones = milestones;
        this.completions = completions;
        this.healthRecords = healthRecords;
    }

    /** 当前用户 6 新手任务进度（无档案 → 404 防枚举）。 */
    @Transactional(readOnly = true)
    public NewbieTaskResponse progress(long ownerId) {
        PetProfile pet = profiles.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        long petProfileId = pet.getId();
        PetType petType = pet.getPetType();

        List<NewbieTaskResponse.Item> items = new ArrayList<>();
        int done = 0;
        for (NewbieTask task : NewbieTask.ALL) {
            boolean completed = task.isHealthRecord()
                    ? healthRecords.existsByPetProfileId(petProfileId)
                    : isMilestoneDone(petProfileId, petType, task.milestoneSuffix());
            if (completed) {
                done++;
            }
            items.add(new NewbieTaskResponse.Item(task.name(), completed));
        }

        boolean lulusUnlocked = isMilestoneDoneByCode(
                petProfileId, MilestoneCatalog.lulusPemulaCode(petType));
        return new NewbieTaskResponse(items, done, NewbieTask.ALL.size(), lulusUnlocked);
    }

    private boolean isMilestoneDone(long petProfileId, PetType petType, String suffix) {
        return isMilestoneDoneByCode(petProfileId, prefixOf(petType) + "-" + suffix);
    }

    private boolean isMilestoneDoneByCode(long petProfileId, String code) {
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

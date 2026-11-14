package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.UserMilestoneFlagRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「Lulus Pemula 已解锁」的唯一读取口（V1.5.0 onboarding-recall Story 2.1）。
 *
 * <p>新旧两版新手任务接口（{@link NewbieTaskService} / {@link LegacyNewbieTaskCompat}）的 {@code lulusPemulaUnlocked}
 * 必须同口径（OR-9），收口在这一处。
 *
 * <p>V1.5.0 Story 2-2 起 Lulus 是账号级 flag：有 {@code LULUS_PEMULA} flag 即解锁；另保留「任一宠物已有 Lulus 完成行」
 * 也算（按旧 6 项解锁、补算迁移 2-4 跑之前的老用户——PRD「已解锁不收回」）。
 */
@Component
public class LulusPemulaReader {

    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;
    private final UserMilestoneFlagRepository flags;

    public LulusPemulaReader(PetMilestoneRepository milestones, MilestoneCompletionRepository completions,
            UserMilestoneFlagRepository flags) {
        this.milestones = milestones;
        this.completions = completions;
        this.flags = flags;
    }

    /**
     * @param ownerId 账号
     * @param pets    账号全部宠物（调用方已查好，避免重复查）
     */
    @Transactional(readOnly = true)
    public boolean unlocked(long ownerId, List<PetProfile> pets) {
        if (flags.existsByUserIdAndFlagKey(ownerId, AccountMilestoneFlag.LULUS_PEMULA.name())) {
            return true;
        }
        return pets.stream().anyMatch(pet -> milestones
                .findByPetProfileIdAndCode(pet.getId(), MilestoneCatalog.lulusPemulaCode(pet.getPetType()))
                .map(m -> completions.existsByPetMilestoneId(m.getId()))
                .orElse(false));
    }
}

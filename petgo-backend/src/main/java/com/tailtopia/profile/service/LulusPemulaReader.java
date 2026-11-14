package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「Lulus Pemula 已解锁」的唯一读取口（V1.5.0 onboarding-recall Story 2.1）。
 *
 * <p>新旧两版新手任务接口（{@link NewbieTaskService} / {@link LegacyNewbieTaskCompat}）的 {@code lulusPemulaUnlocked}
 * 必须同口径（OR-9），收口在这一处。本 story 仍按「任一宠物已完成 Lulus 编码」判定；
 * Story 2-2 把 Lulus 改为账号级 flag 后只改这里。
 */
@Component
public class LulusPemulaReader {

    private final PetMilestoneRepository milestones;
    private final MilestoneCompletionRepository completions;

    public LulusPemulaReader(PetMilestoneRepository milestones, MilestoneCompletionRepository completions) {
        this.milestones = milestones;
        this.completions = completions;
    }

    /**
     * @param ownerId 账号
     * @param pets    账号全部宠物（调用方已查好，避免重复查）
     */
    @Transactional(readOnly = true)
    public boolean unlocked(long ownerId, List<PetProfile> pets) {
        return pets.stream().anyMatch(pet -> milestones
                .findByPetProfileIdAndCode(pet.getId(), MilestoneCatalog.lulusPemulaCode(pet.getPetType()))
                .map(m -> completions.existsByPetMilestoneId(m.getId()))
                .orElse(false));
    }
}

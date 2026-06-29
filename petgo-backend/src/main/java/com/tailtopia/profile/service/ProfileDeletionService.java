package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.media.PersonalMedia;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * profile 模块注销级联删除（Story 7.3）：宠物档案 + 健康事件（含私密桶健康图、公开桶名片图）+ 里程碑
 * （roster + 完成记录，Story 8.1）物理删除。经本 service 接口供 account 编排（禁 account 直 join profile 表）。
 * 返回待删个人图。
 */
@Service
public class ProfileDeletionService {

    private final PetProfileRepository petProfiles;
    private final HealthEventRepository healthEvents;
    private final PetMilestoneRepository petMilestones;
    private final MilestoneCompletionRepository milestoneCompletions;
    private final MilestoneShareRepository milestoneShares;

    public ProfileDeletionService(PetProfileRepository petProfiles, HealthEventRepository healthEvents,
            PetMilestoneRepository petMilestones, MilestoneCompletionRepository milestoneCompletions,
            MilestoneShareRepository milestoneShares) {
        this.petProfiles = petProfiles;
        this.healthEvents = healthEvents;
        this.petMilestones = petMilestones;
        this.milestoneCompletions = milestoneCompletions;
        this.milestoneShares = milestoneShares;
    }

    @Transactional
    public PersonalMedia deleteByUserId(long userId) {
        Optional<PetProfile> profile = petProfiles.findByOwnerId(userId);
        if (profile.isEmpty()) {
            return PersonalMedia.empty();
        }
        PetProfile pet = profile.get();
        long petId = pet.getId();

        List<String> privateKeys = new ArrayList<>();
        for (HealthEvent e : healthEvents.findByPetId(petId)) {
            if (e.getImageKeys() != null) {
                privateKeys.addAll(e.getImageKeys());
            }
        }
        healthEvents.deleteByPetId(petId);

        // 里程碑级联删除（Story 8.1，归 profile 域）：先删完成记录（FK→roster）→ 再删 roster。
        List<Long> milestoneIds = petMilestones.findByPetProfileIdOrderBySortOrderAsc(petId).stream()
                .map(PetMilestone::getId).toList();
        if (!milestoneIds.isEmpty()) {
            milestoneCompletions.deleteByPetMilestoneIdIn(milestoneIds);
        }
        petMilestones.deleteByPetProfileId(petId);
        // 里程碑对外分享（P-35 分享链接）：随档案删除，token 立即失效（防注销后仍可访问 H5）。
        milestoneShares.deleteByPetProfileId(petId);

        List<String> publicUrls = new ArrayList<>();
        if (pet.getAvatarUrl() != null) {
            publicUrls.add(pet.getAvatarUrl());
        }
        if (pet.getOgImageUrl() != null) {
            publicUrls.add(pet.getOgImageUrl());
        }
        petProfiles.delete(pet);

        return new PersonalMedia(privateKeys, publicUrls);
    }
}

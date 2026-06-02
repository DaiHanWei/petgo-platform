package com.petgo.profile.service;

import com.petgo.profile.domain.HealthEvent;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.repository.HealthEventRepository;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.media.PersonalMedia;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * profile 模块注销级联删除（Story 7.3）：宠物档案 + 健康事件（含私密桶健康图、公开桶名片图）物理删除。
 * 经本 service 接口供 account 编排（禁 account 直 join profile 表）。返回待删个人图。
 */
@Service
public class ProfileDeletionService {

    private final PetProfileRepository petProfiles;
    private final HealthEventRepository healthEvents;

    public ProfileDeletionService(PetProfileRepository petProfiles, HealthEventRepository healthEvents) {
        this.petProfiles = petProfiles;
        this.healthEvents = healthEvents;
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

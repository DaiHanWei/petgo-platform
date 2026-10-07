package com.tailtopia.tailsonality.service;

import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityBadgeRepository;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 佩戴切换 / 卸下（V1.3.2 Story 3.3 · AC2 · D-16）。
 *
 * <p>自动佩戴不在这里：它只在发放口的 GRANTED 分支、同一事务内发生（{@code TailsonalityKeepsakeGranter}）。
 */
@Service
public class TailsonalityBadgeService {

    private final TailsonalityBadgeRepository badges;
    private final TailsonalityResultRepository results;
    private final PetProfileQueryService pets;

    public TailsonalityBadgeService(TailsonalityBadgeRepository badges, TailsonalityResultRepository results,
            PetProfileQueryService pets) {
        this.badges = badges;
        this.results = results;
        this.pets = pets;
    }

    /** 非本人 / 不存在 → 404（同 {@code GET …/results/{token}} 口径）；未解锁 → 422 {@code tailsonality-badge-locked}。 */
    @Transactional
    public void equip(long userId, String resultToken) {
        OwnedPetRef pet = requirePet(userId);
        TailsonalityResult r = results.findByPublicTokenAndPetProfileId(resultToken, pet.petId())
                .orElseThrow(() -> AppException.notFound("结果不存在"));
        if (r.getUnlockedAt() == null) {
            throw AppException.tailsonalityBadgeLocked("结果未解锁，不能佩戴");
        }
        badges.upsert(pet.petId(), r.getId());
    }

    /** 卸下：幂等，无佩戴也成功。 */
    @Transactional
    public void unequip(long userId) {
        badges.deleteByPetProfileId(requirePet(userId).petId());
    }

    private OwnedPetRef requirePet(long userId) {
        return pets.findOwnedPet(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }
}

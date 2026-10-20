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
        // V1.4.0 multi-pet（Story 1.2 · X14，token 寻址）：由结果行反查宠物 + 归属校验，佩戴到该宠物。
        TailsonalityResult r = results.findByPublicToken(resultToken)
                .filter(x -> x.getPetProfileId() != null && pets.ownsPet(userId, x.getPetProfileId()))
                .orElseThrow(() -> AppException.notFound("结果不存在"));
        if (r.getUnlockedAt() == null) {
            throw AppException.tailsonalityBadgeLocked("结果未解锁，不能佩戴");
        }
        badges.upsert(r.getPetProfileId(), r.getId());
    }

    /** 卸下：幂等，无佩戴也成功。V1.4.0 multi-pet：指定宠物（null = 默认宠物）。 */
    @Transactional
    public void unequip(long userId, Long petId) {
        badges.deleteByPetProfileId(requirePet(userId, petId).petId());
    }

    private OwnedPetRef requirePet(long userId, Long petId) {
        return pets.findOwnedPet(userId, petId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
    }
}

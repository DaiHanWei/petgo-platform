package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.repository.TailsonalityOwnerTypeRepository;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tailsonality 结果的删档 / 注销级联（V1.3.2 Story 2.1 · AC7.2 · AD-17，安全攸关 D1/D2）。
 *
 * <p>宠物级：由 {@code ProfileDeletionService.deleteByUserId} 在删宠物行<b>之前</b>、同一事务内调用
 * （{@code tailsonality_results.pet_profile_id} 对 {@code pet_profiles} 有 FK、无 ON DELETE）。
 *
 * <p>账号级（Story 2.5）：主人类型由 {@code AccountDeletionService.execute} 在 user 行删除前调用；
 * <b>删档（只删宠物）不删主人类型</b>（AD-17）。
 */
@Service
public class TailsonalityDeletionService {

    private final TailsonalityResultRepository results;
    private final TailsonalityOwnerTypeRepository ownerTypes;

    public TailsonalityDeletionService(TailsonalityResultRepository results,
            TailsonalityOwnerTypeRepository ownerTypes) {
        this.results = results;
        this.ownerTypes = ownerTypes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForPet(long petId) {
        results.deleteByPetProfileId(petId);
    }

    /** 注销级联：主人类型是账号级个人数据，物理删除。幂等。 */
    @Transactional
    public void deleteOwnerTypeByUserId(long userId) {
        ownerTypes.deleteByUserId(userId);
    }
}

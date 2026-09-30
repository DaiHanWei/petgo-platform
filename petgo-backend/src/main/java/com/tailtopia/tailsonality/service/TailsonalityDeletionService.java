package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tailsonality 结果的删档 / 注销级联（V1.3.2 Story 2.1 · AC7.2 · AD-17，安全攸关 D1/D2）。
 *
 * <p>由 {@code ProfileDeletionService.deleteByUserId} 在删宠物行<b>之前</b>、同一事务内调用
 * （{@code tailsonality_results.pet_profile_id} 对 {@code pet_profiles} 有 FK、无 ON DELETE）。
 */
@Service
public class TailsonalityDeletionService {

    private final TailsonalityResultRepository results;

    public TailsonalityDeletionService(TailsonalityResultRepository results) {
        this.results = results;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForPet(long petId) {
        results.deleteByPetProfileId(petId);
    }
}

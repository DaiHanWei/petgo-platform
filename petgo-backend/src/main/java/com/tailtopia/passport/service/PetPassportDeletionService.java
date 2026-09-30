package com.tailtopia.passport.service;

import com.tailtopia.passport.repository.PetPassportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护照的删档 / 注销级联（V1.3.2 Story 1.2 · AC7.1 · AD-17，安全攸关 D1/D2）。
 *
 * <p>由 {@code ProfileDeletionService.deleteByUserId} 在删宠物行<b>之前</b>、同一事务内调用。
 * 删后重建宠物首次打开护照会重新签发（新宠物无 KTP → ISSUED；旧 KTP 卡已被 markProfileDeleted 打标，不会被沿用）。
 */
@Service
public class PetPassportDeletionService {

    private final PetPassportRepository passports;

    public PetPassportDeletionService(PetPassportRepository passports) {
        this.passports = passports;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForPet(long petId) {
        passports.deleteByPetProfileId(petId);
    }
}

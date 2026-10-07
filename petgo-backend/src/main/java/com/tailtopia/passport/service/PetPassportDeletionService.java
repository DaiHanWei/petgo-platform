package com.tailtopia.passport.service;

import com.tailtopia.passport.repository.BoardingPassUnlockRepository;
import com.tailtopia.passport.repository.PassportSnapshotRepository;
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
    private final PassportSnapshotRepository snapshots;
    private final BoardingPassUnlockRepository boardingPasses;

    public PetPassportDeletionService(PetPassportRepository passports, PassportSnapshotRepository snapshots,
            BoardingPassUnlockRepository boardingPasses) {
        this.passports = passports;
        this.snapshots = snapshots;
        this.boardingPasses = boardingPasses;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteForPet(long petId) {
        // V1.3.2 Story 3.4 · AC9：快照物理删除（FK ON DELETE CASCADE 兜底）；对应 keepsake_purchases 保留、pet_profile_id 置空。
        snapshots.deleteByPetProfileId(petId);
        // V1.3.2 Story 3.5 · AC11：登机牌解锁行物理删除（FK CASCADE 兜底）；购买记录保留、pet_profile_id 置空。
        boardingPasses.deleteByPetProfileId(petId);
        passports.deleteByPetProfileId(petId);
    }
}

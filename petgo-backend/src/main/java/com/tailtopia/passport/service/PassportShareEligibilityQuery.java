package com.tailtopia.passport.service;

import com.tailtopia.passport.repository.PetPassportRepository;
import com.tailtopia.place.service.PlaceStampQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分享奖励资格只读口（V1.3.2 Story 4.5 · AC2.3）：share 模块经它读，不跨模块直接注入本模块的仓库。
 *
 * <p>护照卡 = 已签发护照且至少 1 枚章；登机牌卡 = 至少 1 条场所打卡（章 = 打过卡的场所，同一口径）。
 */
@Service
public class PassportShareEligibilityQuery {

    private final PetPassportRepository passports;
    private final PlaceStampQueryService stamps;

    public PassportShareEligibilityQuery(PetPassportRepository passports, PlaceStampQueryService stamps) {
        this.passports = passports;
        this.stamps = stamps;
    }

    /** 已签发护照且至少 1 枚章（护照卡）。 */
    @Transactional(readOnly = true)
    public boolean canSharePage(long petProfileId) {
        return passports.findByPetProfileId(petProfileId).isPresent() && stamps.stampCountOf(petProfileId) >= 1;
    }

    /** 至少 1 条场所打卡（登机牌卡）。 */
    @Transactional(readOnly = true)
    public boolean canShareBoarding(long petProfileId) {
        return stamps.stampCountOf(petProfileId) >= 1;
    }
}

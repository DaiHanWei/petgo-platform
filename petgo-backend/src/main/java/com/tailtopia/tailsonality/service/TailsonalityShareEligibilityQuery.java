package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.repository.TailsonalityOwnerTypeRepository;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分享奖励资格只读口（V1.3.2 Story 4.5 · AC2.3）：share 模块经它读，不跨模块直接注入本模块的仓库。
 *
 * <p>防的是「没生成过卡就刷接口」：结果卡 / 配型卡至少要有一条结果；配型卡另需本人已设主人类型。
 */
@Service
public class TailsonalityShareEligibilityQuery {

    private final TailsonalityResultRepository results;
    private final TailsonalityOwnerTypeRepository ownerTypes;

    public TailsonalityShareEligibilityQuery(TailsonalityResultRepository results,
            TailsonalityOwnerTypeRepository ownerTypes) {
        this.results = results;
        this.ownerTypes = ownerTypes;
    }

    /** 该宠物至少有一条 Tailsonality 结果。 */
    @Transactional(readOnly = true)
    public boolean hasResult(long petProfileId) {
        return results.existsByPetProfileId(petProfileId);
    }

    /** 本人已设主人类型（配型卡的前提）。 */
    @Transactional(readOnly = true)
    public boolean hasOwnerType(long userId) {
        return ownerTypes.findByUserId(userId).isPresent();
    }
}

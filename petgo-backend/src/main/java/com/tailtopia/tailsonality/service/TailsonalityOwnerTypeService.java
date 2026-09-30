package com.tailtopia.tailsonality.service;

import com.tailtopia.tailsonality.dto.OwnerTypeResponse;
import com.tailtopia.tailsonality.repository.TailsonalityOwnerTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 主人四字母类型读写（V1.3.2 Story 2.5 · 账号级；配型不落库，AD-3）。 */
@Service
public class TailsonalityOwnerTypeService {

    private final TailsonalityOwnerTypeRepository ownerTypes;

    public TailsonalityOwnerTypeService(TailsonalityOwnerTypeRepository ownerTypes) {
        this.ownerTypes = ownerTypes;
    }

    @Transactional(readOnly = true)
    public OwnerTypeResponse get(long userId) {
        return new OwnerTypeResponse(ownerTypes.findByUserId(userId).map(t -> t.getTypeCode()).orElse(null));
    }

    /** 调用方已按 {@code ^[EI][NS][TF][JP]$} 校验。 */
    @Transactional
    public OwnerTypeResponse set(long userId, String typeCode) {
        ownerTypes.upsert(userId, typeCode);
        return new OwnerTypeResponse(typeCode);
    }
}

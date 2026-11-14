package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import java.time.Instant;

/**
 * 宠物只读投影（V1.4.0 multi-pet Story 3.1 · {@code PetQueryService}；③ 送礼依赖，<b>签名定死</b>，字段只追加）。
 *
 * @param status 含只读（{@code READ_ONLY}）——③ 只读宠物可收礼，调用方按需判
 */
public record PetView(long id, long ownerId, String name, String avatarUrl, PetType petType,
        PetStatus status, Instant createdAt,
        /** 细分类型（V1.5.0 pet-species · D-10）：只给显示类返回体透传；业务逻辑继续读 {@code petType}。 */
        com.tailtopia.profile.domain.PetSpecies species) {

    /** 便捷构造：V1.5.0 前的形态（细分类型 = 大类同名值）。 */
    public PetView(long id, long ownerId, String name, String avatarUrl, PetType petType, PetStatus status,
            Instant createdAt) {
        this(id, ownerId, name, avatarUrl, petType, status, createdAt,
                petType == null ? null : com.tailtopia.profile.domain.PetSpecies.fromGroup(petType));
    }
}

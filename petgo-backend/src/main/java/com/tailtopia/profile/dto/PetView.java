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
        PetStatus status, Instant createdAt) {
}

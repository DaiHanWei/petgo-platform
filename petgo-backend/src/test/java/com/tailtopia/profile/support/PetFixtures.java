package com.tailtopia.profile.support;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * V1.4.0 multi-pet L0 夹具：造带 id / created_at 的宠物（Mockito 仓储直接返回，绕过库里的唯一约束）。
 */
public final class PetFixtures {

    private PetFixtures() {
    }

    public static PetProfile pet(long ownerId, long id, PetType type, Instant createdAt) {
        PetProfile p = PetProfile.create(ownerId, type, "Pet" + id, null, null, null, null, "TOK" + id);
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", createdAt);
        return p;
    }

    public static PetProfile pet(long ownerId, long id, Instant createdAt) {
        return pet(ownerId, id, PetType.CAT, createdAt);
    }
}

package com.petgo.vet.dto;

import com.petgo.vet.domain.VetAccount;

/**
 * 兽医自身视图（Story 5.1 探活 + 工作台顶部展示）。绝不含 username/passwordHash。
 */
public record VetMeResponse(long id, String displayName, String status) {

    public static VetMeResponse from(VetAccount vet) {
        return new VetMeResponse(vet.getId(), vet.getDisplayName(), vet.getStatus().name());
    }
}

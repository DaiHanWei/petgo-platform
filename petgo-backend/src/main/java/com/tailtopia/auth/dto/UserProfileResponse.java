package com.tailtopia.auth.dto;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;

/**
 * 当前用户聚合视图（{@code GET /api/v1/me} 与登录响应内嵌）。
 *
 * <p>{@code hasPetProfile} 备 Story 1.7/Epic 2：1.7 期恒 false（无 pet_profiles 表），
 * Epic 2 接入后由真实档案驱动（决策见 1.7）。
 */
public record UserProfileResponse(
        Long id,
        String nickname,
        String displayName,
        String email,
        String avatarUrl,
        String signature,
        PetStatus petStatus,
        boolean onboardingCompleted,
        boolean hasPetProfile) {

    public static UserProfileResponse from(User u, boolean hasPetProfile) {
        return new UserProfileResponse(
                u.getId(),
                u.getNickname(),
                u.getDisplayName(),
                // email 为 PII：仅本人 /me 聚合视图返回，绝不进 Feed/作者视图，且日志已禁记。
                u.getEmail(),
                u.getAvatarUrl(),
                u.getSignature(),
                u.getPetStatus(),
                u.isOnboardingCompleted(),
                hasPetProfile);
    }
}

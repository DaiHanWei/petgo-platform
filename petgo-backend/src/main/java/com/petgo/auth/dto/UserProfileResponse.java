package com.petgo.auth.dto;

import com.petgo.auth.domain.PetStatus;
import com.petgo.auth.domain.User;

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
        String avatarUrl,
        PetStatus petStatus,
        boolean onboardingCompleted,
        boolean hasPetProfile) {

    public static UserProfileResponse from(User u, boolean hasPetProfile) {
        return new UserProfileResponse(
                u.getId(),
                u.getNickname(),
                u.getDisplayName(),
                u.getAvatarUrl(),
                u.getPetStatus(),
                u.isOnboardingCompleted(),
                hasPetProfile);
    }
}

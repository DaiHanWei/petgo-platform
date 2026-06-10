package com.petgo.auth.dto;

/**
 * Google 登录响应：自签令牌 + 角色 + 新老用户分流信号 + 用户聚合视图。
 *
 * <p>{@code isNewUser}（本次是否首授权建号）与 {@code onboardingCompleted}（是否已完成引导）
 * 共同驱动前端分流：onboarding 已完成→进 App；否则→新用户引导（Story 1.6）。
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String role,
        boolean isNewUser,
        boolean onboardingCompleted,
        UserProfileResponse profile) {
}

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
        boolean hasPetProfile,
        boolean isSystemDefaultName,
        /**
         * 手机号（V1.1.6 Story 7.1 · FR-70）；未填写 / 已撤回时为 null。
         *
         * <p>🛡 **仅本人 /me 聚合视图返回**，与 email 同一口径 —— 绝不进任何对他人展示的投影。
         *
         * <p>这里下发的是**完整号码**：设置页要展示脱敏形态、编辑抽屉要展示完整号码，
         * 两者都是**同一个人看自己的数据**，脱敏属**显示层**的事，放客户端做（口径只有一处）。
         */
        String phone,
        /**
         * 注册时间（V1.1.6 Story 7.2 · FR-70）。UTC ISO-8601，**时区换算在客户端做**。
         *
         * <p>🔴 **为什么必须下发**：FR-70 手机号软引导的时机是「用户第 3 天打开 App」
         * （决策 X-21），而"第几天"只能从注册时间算。客户端拿不到它就实现不了这条 FR。
         *
         * <p>🔴 **不得用「首次启动时本地记一个日期」替代**：那样**存量用户**（注册已久）
         * 会被当成新人、还要再等两天；重装 App 更是重新计时。注册时间只有服务端知道。
         *
         * <p>⚠️ 非 PII、无敏感性（不含精确行为轨迹），与 `onboardingCompleted` 同一档；
         * `users.created_at` 列早就存在，本字段**不涉及任何迁移**。
         */
        java.time.Instant createdAt) {

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
                hasPetProfile,
                // 内容审核 story 4：昵称是否为违规重置的系统默认编码名（App 可据此轻量引导去改名，非必需）。
                u.isSystemDefaultName(),
                u.getPhone(),
                u.getCreatedAt());
    }
}

package com.petgo.auth.service;

import com.petgo.auth.domain.PetStatus;
import com.petgo.auth.domain.User;
import com.petgo.auth.dto.UpdateMeRequest;
import com.petgo.auth.dto.UserProfileResponse;
import com.petgo.auth.repository.UserRepository;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前用户资料服务（Story 1.6）：昵称确认 + 宠物状态选择 + onboarding 完成。
 *
 * <p>仅作用于传入的当前 JWT 用户 id（调用方从 SecurityContext 取，不接受任意 userId，防越权）。
 * 服务端校验权威：昵称非空且 ≤20、petStatus ∈ {A,B,C}。
 */
@Service
public class MeService {

    private final UserRepository users;
    private final PetProfileRepository petProfiles;

    public MeService(UserRepository users, PetProfileRepository petProfiles) {
        this.users = users;
        this.petProfiles = petProfiles;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getMe(long userId) {
        return UserProfileResponse.from(load(userId), hasPetProfile(userId));
    }

    @Transactional
    public UserProfileResponse updateMe(long userId, UpdateMeRequest req) {
        User user = load(userId);

        if (req.nickname() != null) {
            String nn = req.nickname().trim();
            if (nn.isEmpty()) {
                throw AppException.validation("昵称不能为空");
            }
            if (nn.length() > 20) { // 与 @Size 双保险
                throw AppException.validation("昵称不能超过 20 字");
            }
            user.setNickname(nn);
        }

        if (req.petStatus() != null) {
            PetStatus status = parsePetStatus(req.petStatus());
            user.setPetStatus(status);
            // 首次完成状态选择即置 onboarding 完成（档案创建是可跳过的后续步骤，不阻塞 onboarding）。
            if (!user.isOnboardingCompleted()) {
                user.setOnboardingCompleted(true);
            }
        }

        if (req.avatarUrl() != null) {
            // 头像替换（Story 7.1）：客户端 STS 直传①公开桶后回填，仅存应用自有 URL（不存第三方临时 URL）。
            String url = req.avatarUrl().trim();
            user.setAvatarUrl(url.isEmpty() ? null : url);
        }

        users.save(user);
        return UserProfileResponse.from(user, hasPetProfile(userId));
    }

    private User load(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> AppException.unauthorized("登录已过期，请重新登录"));
    }

    private static PetStatus parsePetStatus(String raw) {
        try {
            return PetStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("宠物状态非法，须为 A/B/C 之一");
        }
    }

    /**
     * hasPetProfile：Story 2.2 起按真实档案查询回填（pet_profiles 已建表）。
     * 兑现 Story 1.7 契约：1.7 期恒 false，Epic 2 接入后由真实档案驱动。
     */
    private boolean hasPetProfile(long userId) {
        return petProfiles.existsByOwnerId(userId);
    }
}

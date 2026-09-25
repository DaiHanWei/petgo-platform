package com.tailtopia.onboarding.service;

import com.tailtopia.onboarding.repository.UserOnboardingMarkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 引导标记的注销级联（V1.3.0 批次 A · Story 5.4 · AC7，CLAUDE.md 安全攸关 D1）。
 *
 * <p>{@code user_onboarding_marks} 是纯个人数据（谁看过哪个引导），随注销**物理删除** ——
 * 与同批次新增的 {@code age_card_share_rewards} 同一口径。幂等可重跑。
 *
 * <p>⚠️ 新增一张带用户外键的表就要同时接上这里。漏接的表在注销后会**留着指向一个
 * 已不存在的人的行**，而这类遗漏没有任何报错会提醒你。
 */
@Service
public class OnboardingMarkDeletionService {

    private final UserOnboardingMarkRepository marks;

    public OnboardingMarkDeletionService(UserOnboardingMarkRepository marks) {
        this.marks = marks;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        marks.deleteByUserId(userId);
    }
}

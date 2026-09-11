package com.tailtopia.onboarding.service;

import com.tailtopia.onboarding.domain.OnboardingMarkKey;
import com.tailtopia.onboarding.domain.UserOnboardingMark;
import com.tailtopia.onboarding.repository.UserOnboardingMarkRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性引导标记的读写（V1.3.0 批次 A · Story 5.4 · AD-A21）。
 *
 * <p>整个服务只有两件事：**读这个用户置位了哪些键**、**置位一个键**。
 * 刻意不提供「取消置位」——引导是一次性的，能取消就不是一次性的了。
 */
@Service
public class OnboardingMarkService {

    private final UserOnboardingMarkRepository marks;

    public OnboardingMarkService(UserOnboardingMarkRepository marks) {
        this.marks = marks;
    }

    /** 这个用户已经看过哪些引导（线格式键名）。 */
    @Transactional(readOnly = true)
    public List<String> marksOf(long userId) {
        return marks.findByUserId(userId).stream().map(UserOnboardingMark::getMarkKey).toList();
    }

    /**
     * 置位一个键。**幂等**：已置位就什么都不做。
     *
     * <p>🛡 先查是为了省一次写；真正兜底的是唯一约束 —— 并发两次置位，
     * 第二次撞键后按「已置位」处理，不外抛。
     */
    @Transactional
    public void mark(long userId, OnboardingMarkKey key) {
        if (marks.existsByUserIdAndMarkKey(userId, key.wire())) {
            return;
        }
        try {
            marks.saveAndFlush(UserOnboardingMark.of(userId, key));
        } catch (DataIntegrityViolationException alreadyMarked) {
            // 并发下别人先置位了 —— 这正是我们想要的结果，不是错误。
        }
    }
}

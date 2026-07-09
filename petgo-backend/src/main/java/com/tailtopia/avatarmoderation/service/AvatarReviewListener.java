package com.tailtopia.avatarmoderation.service;

import com.tailtopia.avatarmoderation.event.AvatarReviewRequestedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 头像送审监听（内容审核 story 5，§5.1/§5.2）。{@code @Async @TransactionalEventListener(AFTER_COMMIT)}：
 * 头像「先放行」已立即生效并提交后，异步线程开局审核（先建 QUEUED 记录 + 陈旧作废旧记录，再调三方图像审核评分路由）。
 *
 * <p><b>独立 bean（非 {@link AvatarModerationService} 内自调）</b>以确保 {@code @Async} 代理生效；三段（开局/评分/落库）
 * 均<b>跨 bean</b>委托给 service 的独立事务方法——若在 service 内自调用 {@code applyScoreOutcome} 会绕过代理
 * → REQUIRES_NEW 失效、更新不 flush，记录卡 QUEUED（cm-4 已踩此坑并修复，本 story 照搬正确编排）。
 */
@Component
public class AvatarReviewListener {

    private final AvatarModerationService service;

    public AvatarReviewListener(AvatarModerationService service) {
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAvatarReviewRequested(AvatarReviewRequestedEvent event) {
        long reviewId = service.openReview(event.subjectType(), event.subjectId(), event.avatarUrl());
        // 三段均跨 bean 调 service，确保 openReview / applyScoreOutcome 的 REQUIRES_NEW 代理生效。
        AvatarModerationService.ScoredRouting scored = service.scoreAndRoute(reviewId, event.avatarUrl());
        service.applyScoreOutcome(reviewId, scored.decision(), scored.retries());
    }
}

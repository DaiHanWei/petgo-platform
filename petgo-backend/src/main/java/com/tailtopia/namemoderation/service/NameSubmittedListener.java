package com.tailtopia.namemoderation.service;

import com.tailtopia.namemoderation.event.NameSubmittedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 名称提交送审监听（内容审核 story 4，§5.3）。{@code @Async @TransactionalEventListener(AFTER_COMMIT)}：
 * 名称「先放行」已立即生效并提交后，异步线程开局审核（先建 SCORING 记录 + 陈旧作废旧记录，再调三方评分路由）。
 *
 * <p>独立 bean（非 {@link NameModerationService} 内自调）以确保 {@code @Async} 代理生效；两段（开局/评分）
 * 委托给 {@link NameModerationService} 的独立事务方法，保证记录先落库、评分结果按 revision 陈旧作废。
 */
@Component
public class NameSubmittedListener {

    private final NameModerationService service;

    public NameSubmittedListener(NameModerationService service) {
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNameSubmitted(NameSubmittedEvent event) {
        long recordId = service.openReview(event.targetType(), event.targetRefId(), event.value());
        // 三段均跨 bean 调用 service，确保 openReview / applyScoreOutcome 的 REQUIRES_NEW 代理生效
        // （若在 service 内自调用 applyScoreOutcome 会绕过代理 → 更新不落库，记录卡 SCORING）。
        NameModerationService.ScoredRouting scored = service.scoreAndRoute(recordId, event.value());
        service.applyScoreOutcome(recordId, scored.decision(), scored.retries());
    }
}

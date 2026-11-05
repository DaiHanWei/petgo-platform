package com.tailtopia.profile.service;

import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.social.event.UserFollowedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 账号级里程碑触发订阅（V1.4.2 social-topic Story 2.1 · FR-154 · D-12 / P-3）。
 *
 * <ul>
 *   <li>{@link UserFollowedEvent}（只在 {@code FollowService.follow} 真正新建关注行时发）→「第一次关注一个人」。
 *       系统自动关注官方号（{@code source=SYSTEM}）不发此事件 → 天然不触发；追更宠物（{@code PetFollowedEvent}）本类不监听。</li>
 *   <li>{@link ContentCollectedEvent}（只在帖子收藏真正新增时发）→「第一次收藏内容」。🔴 <b>不跳过自收藏</b>
 *       （PRD AC3.4）；场所收藏不发此事件 → 不触发。</li>
 * </ul>
 *
 * <p>写法：{@code @Async} + {@code @TransactionalEventListener(AFTER_COMMIT)}；写库在
 * {@link AccountMilestoneService#onFlagTriggered}（{@code REQUIRES_NEW}，另一个 bean，经代理调用 —— 本类不自调用）。
 * 失败只记 ERROR（userId / flagKey / 异常类名），不重试（V1 姿态）。
 */
@Component
public class AccountMilestoneListener {

    private static final Logger log = LoggerFactory.getLogger(AccountMilestoneListener.class);

    private final AccountMilestoneService accountMilestones;

    public AccountMilestoneListener(AccountMilestoneService accountMilestones) {
        this.accountMilestones = accountMilestones;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserFollowed(UserFollowedEvent e) {
        trigger(e.followerId(), AccountMilestoneFlag.FIRST_FOLLOW_USER);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentCollected(ContentCollectedEvent e) {
        trigger(e.collectorId(), AccountMilestoneFlag.FIRST_COLLECT_CONTENT);
    }

    private void trigger(long userId, AccountMilestoneFlag flag) {
        try {
            accountMilestones.onFlagTriggered(userId, flag);
        } catch (RuntimeException ex) {
            log.error("account milestone trigger failed: userId={} flagKey={} error={}", userId, flag.name(),
                    ex.getClass().getSimpleName());
        }
    }
}

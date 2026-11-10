package com.tailtopia.notify.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.social.event.PetFollowedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 被追更通知（V1.4.2 social-topic · Story 1.2 · AC5 · D-10 / P-3）：消费 Story 1.1 的 {@link PetFollowedEvent}，
 * 给宠物主人发 {@code PET_FOLLOWED}（不聚合；{@code targetRef} = 追更者 user id、宠物 id 存 {@code variant}）。
 *
 * <p>任一命中即不发（不写行、不加角标、不推送）：
 * <ol>
 *   <li>追更者 = 主人（双保险，追更接口已拒）；</li>
 *   <li><b>任一方向拉黑</b>：主人隐藏了追更者（{@code isHidden}，拉黑与举报隐藏都算，同 {@link FollowNotifyListener}），
 *       或追更者拉黑了主人（{@code isBlocked}；追更接口已 403，这里双保险）—— PRD AC9「A 不收到 B 的追更通知」；</li>
 *   <li>主人非 {@code isActive}（注销 / {@code DEACTIVATED} 期间不发）；</li>
 *   <li>24 小时内已有「接收人 × 追更者 × 宠物」的 {@code PET_FOLLOWED}（取消再追更不重复打扰；不同宠物分别通知）。</li>
 * </ol>
 *
 * <p>🔴 写库纪律（P-3）：AFTER_COMMIT 阶段触发事务已提交，本方法自开 {@code REQUIRES_NEW}（不加入已提交的事务），
 * 写库再经 {@link NotificationService#sendPetFollowed}（{@code REQUIRES_NEW}，另一个 bean，过代理，独立提交后才推送）。单条事件、无扇出，不加 {@code @Async}（同 {@link FollowNotifyListener}）。
 * 日志只记 id。
 */
@Component
public class PetFollowNotifyListener {

    /** 被追更通知去重窗口（D-10，同新粉丝）。 */
    static final Duration DEDUP_WINDOW = Duration.ofHours(24);

    private final NotificationService notificationService;
    private final UserHideRelationReader hideRelations;
    private final NotificationRepository notifications;
    private final AccountQueryService accounts;
    private final Clock clock;

    @Autowired
    public PetFollowNotifyListener(NotificationService notificationService, UserHideRelationReader hideRelations,
            NotificationRepository notifications, AccountQueryService accounts) {
        this(notificationService, hideRelations, notifications, accounts, Clock.systemUTC());
    }

    PetFollowNotifyListener(NotificationService notificationService, UserHideRelationReader hideRelations,
            NotificationRepository notifications, AccountQueryService accounts, Clock clock) {
        this.notificationService = notificationService;
        this.hideRelations = hideRelations;
        this.notifications = notifications;
        this.accounts = accounts;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPetFollowed(PetFollowedEvent event) {
        long follower = event.followerId();
        long owner = event.ownerId();
        long petId = event.petProfileId();
        if (follower == owner) {
            return;
        }
        if (hideRelations.isHidden(owner, follower) || hideRelations.isBlocked(follower, owner)) {
            return;
        }
        if (!accounts.isActive(owner)) {
            return;
        }
        if (notifications.existsPetFollowedNotificationSince(owner, follower, String.valueOf(petId),
                clock.instant().minus(DEDUP_WINDOW))) {
            return;
        }
        notificationService.sendPetFollowed(owner, follower, petId);
    }
}

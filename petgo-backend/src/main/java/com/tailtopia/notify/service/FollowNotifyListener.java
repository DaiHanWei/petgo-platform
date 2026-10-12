package com.tailtopia.notify.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.social.event.UserFollowedEvent;
import com.tailtopia.social.read.UserHideRelationReader;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 新粉丝通知（V1.3.4 follow · Story 3.2 · FR-141.11 / AD-4 / D-4）：消费 {@link UserFollowedEvent}，
 * 给被关注者发 {@code USER_FOLLOWED}（不聚合、每次都推、正文空；{@code targetRef} = 关注者数字 userId，T-34）。
 *
 * <p>任一命中即不发（不写行、不加角标、不推送）：
 * <ol>
 *   <li>自关注（双重保险，关注接口已 422）；</li>
 *   <li>接收者隐藏了关注者（{@code isHidden}，拉黑与举报隐藏都算，同 {@code ContentNotifyListener} 判据①；
 *       含 D-4「B 拉黑 A，A 关注 B」—— 关注照常成功，B 收不到通知）；</li>
 *   <li>接收者是官方内容账号（D-9：官方号不接收通知推送、不登录 App）；</li>
 *   <li>24 小时内已有「A 关注了 B」的通知（取关后再关注不重复打扰）。被抑制的那次没有通知行，不计入去重。</li>
 * </ol>
 *
 * <p>⚠️ 抑制写在这里，不写进 {@link NotificationService}（理由同 {@code ContentNotifyListener}）。
 * 24 小时去重是「先查后写」：同一对用户毫秒级内连续两次新建关注（受 30 次 / 分钟限流）理论上可能各发一条，
 * 不为此加锁（代价与收益不对等）。日志只记 id。
 */
@Component
public class FollowNotifyListener {

    /** 新粉丝通知去重窗口（AD-4）。 */
    static final Duration DEDUP_WINDOW = Duration.ofHours(24);

    private final NotificationService notificationService;
    private final UserHideRelationReader hideRelations;
    private final NotificationRepository notifications;
    private final AccountQueryService accounts;
    private final Clock clock;

    @Autowired
    public FollowNotifyListener(NotificationService notificationService, UserHideRelationReader hideRelations,
            NotificationRepository notifications, AccountQueryService accounts) {
        this(notificationService, hideRelations, notifications, accounts, Clock.systemUTC());
    }

    FollowNotifyListener(NotificationService notificationService, UserHideRelationReader hideRelations,
            NotificationRepository notifications, AccountQueryService accounts, Clock clock) {
        this.notificationService = notificationService;
        this.hideRelations = hideRelations;
        this.notifications = notifications;
        this.accounts = accounts;
        this.clock = clock;
    }

    @TransactionalEventListener
    public void onUserFollowed(UserFollowedEvent event) {
        long follower = event.followerId();
        long followee = event.followeeId();
        if (follower == followee) {
            return;
        }
        if (hideRelations.isHidden(followee, follower)) {
            return;
        }
        if (accounts.isOfficialAccount(followee)) {
            return;
        }
        if (notifications.existsFollowedNotificationSince(followee, follower, clock.instant().minus(DEDUP_WINDOW))) {
            return;
        }
        notificationService.sendInteraction(followee, NotificationType.USER_FOLLOWED, follower, null,
                String.valueOf(follower), null, null, null);
    }
}

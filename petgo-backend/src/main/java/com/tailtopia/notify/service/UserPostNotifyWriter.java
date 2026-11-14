package com.tailtopia.notify.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注更新「登记日志 + 写通知」的<b>同一事务</b>（V1.5.0 onboarding-recall Story 5.1 · OR-13）。
 *
 * <p>独立 bean（{@link UserPostNotifyListener} 经代理调用，不自调用）；{@code REQUIRES_NEW}：监听器在 {@code @Async}
 * 线程里无事务，每位收件人各自提交。{@link NotificationService#sendUserPostPublished} 是 {@code REQUIRED}，加入本事务
 * （日志行与通知同成同败）；推送决策在本事务提交之后。
 */
@Component
public class UserPostNotifyWriter {

    private final NotificationService notifications;

    public UserPostNotifyWriter(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * @return {@code true} = 已写通知；{@code false} = 同收件人同帖已登记过（重复事件），什么都没写
     * @throws RuntimeException 写通知失败（事务回滚，日志不残留）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean write(long followerId, long postId, long authorId, String authorNickname, String petName,
            boolean isDiary) {
        return notifications.sendUserPostPublished(followerId, postId, authorId, authorNickname, petName,
                isDiary) != null;
    }
}

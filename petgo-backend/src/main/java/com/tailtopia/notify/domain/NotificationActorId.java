package com.tailtopia.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** {@link NotificationActor} 的复合主键 {@code (notification_id, actor_user_id)}（V1.3.4 follow · Story 3.1）。 */
@Embeddable
public class NotificationActorId implements Serializable {

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    protected NotificationActorId() {
    }

    public NotificationActorId(long notificationId, long actorUserId) {
        this.notificationId = notificationId;
        this.actorUserId = actorUserId;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NotificationActorId other
                && Objects.equals(notificationId, other.notificationId)
                && Objects.equals(actorUserId, other.actorUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(notificationId, actorUserId);
    }
}

package com.tailtopia.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 通知触发人（V1.3.4 follow · Story 3.1 · AD-3）：一行 = 某人触发了某条通知。
 *
 * <p>只存 id 与时间，<b>不存昵称</b>（读取时现查，改名即时生效）。写入一律走
 * {@code NotificationAggregateDao.addActor}（{@code ON CONFLICT DO NOTHING}，同一人不重复）；本实体只读映射。
 */
@Entity
@Table(name = "notification_actors")
public class NotificationActor {

    @EmbeddedId
    private NotificationActorId id;

    @Column(name = "acted_at", nullable = false)
    private Instant actedAt;

    protected NotificationActor() {
    }

    public NotificationActorId getId() {
        return id;
    }

    public Instant getActedAt() {
        return actedAt;
    }
}

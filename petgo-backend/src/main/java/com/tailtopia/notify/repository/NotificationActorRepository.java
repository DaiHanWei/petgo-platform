package com.tailtopia.notify.repository;

import com.tailtopia.notify.domain.NotificationActor;
import com.tailtopia.notify.domain.NotificationActorId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 通知触发人读取（V1.3.4 follow · Story 3.1）。写入走 {@code NotificationAggregateDao}。 */
public interface NotificationActorRepository extends JpaRepository<NotificationActor, NotificationActorId> {

    /** 某条通知的全部触发人，最新在前（{@code acted_at DESC}，同刻按 id 倒序定序）。 */
    @Query("SELECT a.id.actorUserId FROM NotificationActor a WHERE a.id.notificationId = :notificationId "
            + "ORDER BY a.actedAt DESC, a.id.actorUserId DESC")
    List<Long> findActorIdsNewestFirst(@Param("notificationId") long notificationId);

    /**
     * 一页通知的全部触发人行（Story 3.3 · AC7 ①）—— <b>一次</b>取全，每条通知内按 {@code acted_at DESC, actor DESC}。
     * ⚠️ 已知限制：未读期间持续合并的爆款帖一条通知可挂很多 actor 行，{@code IN} 结果随之变长；V1 规模可接受。
     * 调用方保证 {@code notificationIds} 非空。
     */
    @Query("SELECT a FROM NotificationActor a WHERE a.id.notificationId IN :notificationIds "
            + "ORDER BY a.id.notificationId, a.actedAt DESC, a.id.actorUserId DESC")
    List<NotificationActor> findByNotificationIds(
            @Param("notificationIds") java.util.Collection<Long> notificationIds);
}

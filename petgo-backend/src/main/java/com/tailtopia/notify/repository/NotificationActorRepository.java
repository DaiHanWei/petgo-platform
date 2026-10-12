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
}

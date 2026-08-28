package com.tailtopia.notify.repository;

import com.tailtopia.notify.domain.LifecyclePushMark;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

/** 生命周期推送去重标记仓储（留存手册抓手 1）。 */
public interface LifecyclePushMarkRepository extends JpaRepository<LifecyclePushMark, Long> {

    boolean existsByUserIdAndPushKindAndNodeKey(Long userId, String pushKind, String nodeKey);

    /** 每日体检取数：某时刻之后已投递的条数（用于「今天还能发多少」与运营日报）。 */
    long countByPushedAtAfter(Instant since);
}

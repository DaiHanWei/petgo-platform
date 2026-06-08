package com.petgo.notify.repository;

import com.petgo.notify.domain.ScheduledPushMark;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 定时推送去重标记仓库（Story 6.7）。
 *
 * <p>{@code existsBy...} 用于扫描前判定该节点是否已推；插入触发唯一约束 = 去重兜底（并发/重扫安全）。
 */
public interface ScheduledPushMarkRepository extends JpaRepository<ScheduledPushMark, Long> {

    boolean existsByPetProfileIdAndPushKindAndNodeKey(long petProfileId, String pushKind, String nodeKey);
}

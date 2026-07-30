package com.tailtopia.namemoderation.repository;

import com.tailtopia.namemoderation.domain.NameModerationRecord;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NameTargetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 名称审核记录仓储（内容审核 story 4）。 */
public interface NameModerationRecordRepository extends JpaRepository<NameModerationRecord, Long> {

    /** 某 target 的最新一条记录（取最大 revision），用于计算下一版本号。 */
    Optional<NameModerationRecord> findTopByTargetTypeAndTargetRefIdOrderByRevisionDesc(
            NameTargetType targetType, long targetRefId);

    /** 某 target 全部非终态记录（陈旧作废：新提交时置 SUPERSEDED）。 */
    List<NameModerationRecord> findByTargetTypeAndTargetRefIdAndStatusIn(
            NameTargetType targetType, long targetRefId, List<NameModerationStatus> statuses);

    /** 人工队列列表（story 8 后台复用），按提交时间升序。 */
    List<NameModerationRecord> findByStatusOrderBySubmittedAtAsc(NameModerationStatus status);
}

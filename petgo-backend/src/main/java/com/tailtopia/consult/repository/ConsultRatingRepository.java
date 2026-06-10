package com.petgo.consult.repository;

import com.petgo.consult.domain.ConsultRating;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 问诊评分持久层（Story 5.6）。Admin 评分查看（按兽医历史 + 平均分）经 service 聚合。
 */
public interface ConsultRatingRepository extends JpaRepository<ConsultRating, Long> {

    List<ConsultRating> findByVetIdOrderByCreatedAtDesc(long vetId);

    boolean existsBySessionId(long sessionId);

    /** 某会话的评分（Story 5.8 历史展示用户评分）。 */
    Optional<ConsultRating> findBySessionId(long sessionId);

    /** Story 7.3：注销匿名化——某用户全部评分（剥 user_id，保留 stars/comment 供运营 FR-33）。 */
    List<ConsultRating> findByUserId(long userId);
}

package com.tailtopia.content.autolike;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 自动点赞留档（只读；写入走计划器 / 派发器的原生 SQL）。 */
public interface AutoLikeLogRepository extends JpaRepository<AutoLikeLog, Long> {

    List<AutoLikeLog> findByRunIdOrderByIdAsc(long runId);

    Optional<AutoLikeLog> findByPostIdAndRunId(long postId, long runId);
}

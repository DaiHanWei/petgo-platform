package com.tailtopia.growth.repository;

import com.tailtopia.growth.domain.GrowthRecalcJob;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 重算任务（Story 4.1 · AD-12）。状态迁移一律条件 UPDATE（CAS），不读改写实体。 */
public interface GrowthRecalcJobRepository extends JpaRepository<GrowthRecalcJob, Long> {

    /** 最近一次（页面显示）。 */
    Optional<GrowthRecalcJob> findTopByOrderByIdDesc();

    boolean existsByStatusIn(Collection<GrowthRecalcJob.Status> statuses);

    @Modifying
    @Query(value = "UPDATE growth_recalc_jobs SET status = 'RUNNING', started_at = :now "
            + "WHERE id = :id AND status = 'PENDING'", nativeQuery = true)
    int markRunning(@Param("id") long id, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE growth_recalc_jobs SET status = 'DONE', upgraded = :up, downgraded = :down, "
            + "finished_at = :now WHERE id = :id AND status = 'RUNNING'", nativeQuery = true)
    int markDone(@Param("id") long id, @Param("up") int up, @Param("down") int down, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE growth_recalc_jobs SET status = 'FAILED', failure_reason = :reason, finished_at = :now "
            + "WHERE id = :id AND status IN ('PENDING', 'RUNNING')", nativeQuery = true)
    int markFailed(@Param("id") long id, @Param("reason") String reason, @Param("now") Instant now);
}

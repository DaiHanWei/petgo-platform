package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlaceCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code place_checkins} 仓储（Story 5.1）。 */
public interface PlaceCheckinRepository extends JpaRepository<PlaceCheckin, Long> {

    long countByPlaceId(Long placeId);

    /**
     * 合并（Story 5.3）：打卡整批改指保留场所。V1.3.2：护照章是打卡的聚合，改挂后自动并章、无需监听；
     * 登机牌解锁行由 {@code BoardingPassMergeService} 在合并事务内同步改挂。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AdminPlaceCheckin c set c.placeId = :keepId where c.placeId = :mergedId")
    int reassignPlace(@Param("mergedId") long mergedId, @Param("keepId") long keepId);
}

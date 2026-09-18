package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlaceAttitude;
import com.tailtopia.admin.places.domain.PlaceComment;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code place_comments} 仓储（Story 5.1）。 */
public interface PlaceCommentRepository extends JpaRepository<PlaceComment, Long> {

    List<PlaceComment> findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long placeId);

    /** 抽屉评论分页（Story 5.2，每页 20）。 */
    Page<PlaceComment> findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long placeId, Pageable pageable);

    long countByPlaceIdAndDeletedAtIsNull(Long placeId);

    long countByPlaceIdAndAttitudeAndDeletedAtIsNull(Long placeId, PlaceAttitude attitude);

    /** 合并（Story 5.3）：整批改指保留场所；返回迁移行数。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PlaceComment c set c.placeId = :keepId where c.placeId = :mergedId")
    int reassignPlace(@Param("mergedId") long mergedId, @Param("keepId") long keepId);
}

package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.PlacePhoto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code place_photos} 仓储（Story 5.1）。 */
public interface PlacePhotoRepository extends JpaRepository<PlacePhoto, Long> {

    List<PlacePhoto> findByPlaceIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long placeId);

    long countByPlaceIdAndDeletedAtIsNull(Long placeId);

    /** 合并（Story 5.3）：整批改指保留场所；返回迁移行数。须在事务内。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PlacePhoto p set p.placeId = :keepId where p.placeId = :mergedId")
    int reassignPlace(@Param("mergedId") long mergedId, @Param("keepId") long keepId);
}

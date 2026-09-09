package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code places} 仓储（Story 5.1）。列表查询默认 {@code deleted_at IS NULL}（AC2）。 */
public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByPublicToken(String publicToken);

    Optional<Place> findByIdAndDeletedAtIsNull(Long id);

    long countByStatusAndDeletedAtIsNull(PlaceStatus status);

    /** 合并用行锁读取（Story 5.3：并发合并 / 合并 + 下架只成功一个）。须在事务内。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Place p where p.id = :id")
    Optional<Place> findForUpdateById(@Param("id") Long id);
}

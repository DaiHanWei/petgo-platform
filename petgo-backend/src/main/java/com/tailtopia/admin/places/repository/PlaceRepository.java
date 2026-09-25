package com.tailtopia.admin.places.repository;

import com.tailtopia.admin.places.domain.Place;
import com.tailtopia.admin.places.domain.PlaceStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code places} 仓储（Story 5.1）。列表查询默认 {@code deleted_at IS NULL}（AC2）。 */
// Bean 名与 App 侧 com.tailtopia.place.repository.PlaceRepository 区分（同名 Spring Data 仓库 = 启动即 ConflictingBeanDefinition，2026-09-18 场所表对齐）。
@org.springframework.stereotype.Repository("adminPlaceRepository")
public interface PlaceRepository extends JpaRepository<Place, Long> {

    Optional<Place> findByPublicToken(String publicToken);

    Optional<Place> findByIdAndDeletedAtIsNull(Long id);

    long countByStatusAndDeletedAtIsNull(PlaceStatus status);

    /** 合并用行锁读取（Story 5.3：并发合并 / 合并 + 下架只成功一个）。须在事务内。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AdminPlace p where p.id = :id")
    Optional<Place> findForUpdateById(@Param("id") Long id);

    /**
     * 合并链压平（契约 X-1 单跳）：原先并入 {@code mergedId} 的场所改指 {@code keepId}。
     * 否则 B→A 之后再 A→C，B 仍指向已 MERGED 的 A，App 单跳直链落到死链。返回改指行数。须在事务内。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AdminPlace p set p.mergedIntoId = :keepId, p.updatedAt = :now where p.mergedIntoId = :mergedId")
    int repointMergedInto(@Param("mergedId") long mergedId, @Param("keepId") long keepId, @Param("now") Instant now);
}

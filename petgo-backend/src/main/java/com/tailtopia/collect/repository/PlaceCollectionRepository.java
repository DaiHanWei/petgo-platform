package com.tailtopia.collect.repository;

import com.tailtopia.collect.domain.PlaceCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 场所收藏读写（V1.4.0 ④ Story 2.1 · AD-9 / P-6）。
 *
 * <p>合并口径（P-6，只跳一层，与 {@code PlaceRepository.resolveForView} 一致）：收藏一律落在解析后的保留场所上；
 * 「是否已收藏」看保留场所本身 <b>或</b> 任一并入它的场所上的本人行（合并前收藏的被并方）。
 */
public interface PlaceCollectionRepository extends JpaRepository<PlaceCollection, Long> {

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO place_collections (user_id, place_id) VALUES (:userId, :placeId)
            ON CONFLICT (user_id, place_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("userId") long userId, @Param("placeId") long placeId);

    /** 本人在 {@code placeId} 本身或任一 {@code merged_into_id = placeId} 的场所上有行。 */
    @Query(nativeQuery = true, value = """
            SELECT EXISTS (SELECT 1 FROM place_collections pc
                            WHERE pc.user_id = :userId
                              AND (pc.place_id = :placeId
                                   OR pc.place_id IN (SELECT id FROM places WHERE merged_into_id = :placeId)))
            """)
    boolean existsCovering(@Param("userId") long userId, @Param("placeId") long placeId);

    /**
     * 删掉本人在「同一显示条目」上的全部行：原 token 那一行 + 保留场所那一行 + 其他已并入保留场所的本人行
     * （否则删完保留场所，合并前收藏的被并方又会顶上来显示成「还在收藏」）。只删本人。
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM place_collections
             WHERE user_id = :userId
               AND (place_id = :rawId OR place_id = :targetId
                    OR place_id IN (SELECT id FROM places WHERE merged_into_id = :targetId))
            """)
    int deleteForDisplayItem(@Param("userId") long userId, @Param("rawId") long rawId,
            @Param("targetId") long targetId);

    /** 注销：删本人全部场所收藏。 */
    @Modifying
    @Query("delete from PlaceCollection c where c.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}

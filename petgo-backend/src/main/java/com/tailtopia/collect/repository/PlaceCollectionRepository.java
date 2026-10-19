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
 *
 * <p>🔴 「并入」一律指 {@code merged_into_id = X AND deleted_at IS NULL}（已软删的 MERGED 行不跳转，与
 * {@code resolveForView} 先过软删过滤一致）—— 列表去重、详情覆盖、取消删除三处同一口径，否则会出现
 * 「列表里分成两条、删一条连带删掉另一条」（Story 2.2 code-review）。
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
                                   OR pc.place_id IN (SELECT id FROM places
                                                       WHERE merged_into_id = :placeId AND deleted_at IS NULL)))
            """)
    boolean existsCovering(@Param("userId") long userId, @Param("placeId") long placeId);

    /**
     * 删掉本人在「同一显示条目」上的全部行：原 token 那一行 + 保留场所那一行 + 其他已并入两者之一的本人行
     * （否则删完保留场所，合并前收藏的被并方又会顶上来显示成「还在收藏」）。只删本人。
     *
     * <p>「并入 {@code rawId}」也要删（Story 2.2 code-review）：合并链 A→B→C 时列表按一跳把 A 的行显示成 B，
     * 用户拿 B 的 token 移除，只删「并入 C」的行会让 A 那条永远删不掉。代价是链式合并这种罕见情形下会连带移除 C 那一条。
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM place_collections
             WHERE user_id = :userId
               AND (place_id = :rawId OR place_id = :targetId
                    OR place_id IN (SELECT id FROM places
                                     WHERE merged_into_id IN (:rawId, :targetId) AND deleted_at IS NULL))
            """)
    int deleteForDisplayItem(@Param("userId") long userId, @Param("rawId") long rawId,
            @Param("targetId") long targetId);

    /**
     * 我的场所收藏分页（V1.4.0 ④ Story 2.2 · P-6）：先按「有效场所」去重（MERGED 且未软删 → 跳保留场所一层，
     * 与 {@code resolveForView} 一致），每组只留最新一条；<b>游标条件套在去重之后的外层</b>，
     * 保证同一场所不会在后续页以较旧的那条重新出现。
     *
     * <p>代价：每页扫该用户全部场所收藏（个人量级几十～几百，走 {@code (user_id, …)} 索引），不加缓存 / 冗余列。
     *
     * @return 每行 {@code [collectionId(Long), createdAt(Timestamp/Instant), effectivePlaceId(Long)]}
     */
    @Query(nativeQuery = true, value = """
            SELECT d.id, d.created_at, d.eff_place_id FROM (
                SELECT DISTINCT ON (e.eff_place_id) pc.id, pc.created_at, e.eff_place_id
                  FROM place_collections pc
                  JOIN places p ON p.id = pc.place_id
                  CROSS JOIN LATERAL (SELECT CASE
                       WHEN p.status = 'MERGED' AND p.deleted_at IS NULL AND p.merged_into_id IS NOT NULL
                       THEN p.merged_into_id ELSE pc.place_id END AS eff_place_id) e
                 WHERE pc.user_id = :me
                 ORDER BY e.eff_place_id, pc.created_at DESC, pc.id DESC
            ) d
            WHERE d.created_at < :ts OR (d.created_at = :ts AND d.id < :lastId)
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit
            """)
    java.util.List<Object[]> findDedupedPage(@Param("me") long me, @Param("ts") java.time.Instant ts,
            @Param("lastId") long lastId, @Param("limit") int limit);

    /** 注销：删本人全部场所收藏。 */
    @Modifying
    @Query("delete from PlaceCollection c where c.userId = :userId")
    int deleteByUserId(@Param("userId") long userId);
}

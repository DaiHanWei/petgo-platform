package com.tailtopia.place.repository;

import com.tailtopia.place.domain.PlacePhoto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 场所照片读取（V1.3.0 batch-b1 Story 1.9）。
 *
 * <h2>🔴 可见性：VISIBLE 或上传者本人</h2>
 * 补充的照片先发后审 —— 过审前只有上传者自己看得见（否则他一传完就发现照片"没上去"）。
 * 游客（{@code viewerId} 为 null）只看得到 VISIBLE 的。
 *
 * <p>⚠️ 照片**不套拉黑过滤**（与评论有意不同）：一张店门口的照片不是"某个人说的话"，
 * 它是这个场所的客观资料。把它按人过滤掉，拉黑者看到的会是一个照片缺了几张的场所 ——
 * 而他并不知道为什么。举报违规照片走举报链路，不是拉黑。
 */
public interface PlacePhotoRepository extends JpaRepository<PlacePhoto, Long> {

    /** 某场所对 viewer 可见的照片，按展示顺序。 */
    @Query("""
            SELECT p FROM PlacePhoto p
            WHERE p.placeId = :placeId AND p.deletedAt IS NULL
              AND (p.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
                   OR (:hasViewer = true AND p.uploaderId = :viewerId))
            ORDER BY p.sortOrder ASC, p.id ASC
            """)
    List<PlacePhoto> findVisible(@Param("placeId") long placeId,
            @Param("hasViewer") boolean hasViewer,
            @Param("viewerId") Long viewerId);

    /**
     * 一批场所的**对外可见**照片（列表页用：首图 + 张数）。
     *
     * <p>⚠️ 列表口径**只认 VISIBLE**，不含"上传者自己的挂起照片" ——
     * 列表是概览，没必要为一个人的待审照片让列表里的张数跳来跳去；详情页才有那个自视豁免。
     *
     * <p>🔴 一次取回整页（AD-6），调用方在内存里分组，**不要按场所逐个查**。
     */
    @Query("""
            SELECT p FROM PlacePhoto p
            WHERE p.placeId IN :placeIds AND p.deletedAt IS NULL
              AND p.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
            ORDER BY p.placeId ASC, p.sortOrder ASC, p.id ASC
            """)
    List<PlacePhoto> findVisibleForPlaces(@Param("placeIds") List<Long> placeIds);

    /** 当前最大展示顺序（补充的照片排在它后面）。没有照片时返回 null。 */
    @Query("SELECT MAX(p.sortOrder) FROM PlacePhoto p "
            + "WHERE p.placeId = :placeId AND p.deletedAt IS NULL")
    Integer maxSortOrder(@Param("placeId") long placeId);

    /**
     * 某场所**占着位置**的照片张数（补充时的 9 张上限用）。
     *
     * <p>🔴 **不能简单地数"未删的行"**（code-review 2026-09-15）：
     * `REJECTED`（审核判死）与 `AUTHOR_DEACTIVATED`（上传者注销）的行虽然没被软删，
     * 但**谁都看不见它们** —— 把它们算进上限，会让一个界面上只有 4 张照片的场所
     * 永远加不进第 5 张，而用户没有任何办法腾出位置（那些行他看不见，也删不掉）。
     *
     * <p>口径：VISIBLE（对外可见）+ UNDER_REVIEW（待审，随时可能变成可见，必须占位）。
     */
    @Query("""
            SELECT COUNT(p) FROM PlacePhoto p
            WHERE p.placeId = :placeId AND p.deletedAt IS NULL
              AND p.moderationStatus IN (
                  com.tailtopia.content.domain.CommentModerationStatus.VISIBLE,
                  com.tailtopia.content.domain.CommentModerationStatus.UNDER_REVIEW)
            """)
    long countOccupyingSlots(@Param("placeId") long placeId);

    /** 某场所当前**对外可见**的照片张数（删除时的"不许删到零张"用）。 */
    @Query("""
            SELECT COUNT(p) FROM PlacePhoto p
            WHERE p.placeId = :placeId AND p.deletedAt IS NULL
              AND p.moderationStatus = com.tailtopia.content.domain.CommentModerationStatus.VISIBLE
            """)
    long countVisible(@Param("placeId") long placeId);

    Optional<PlacePhoto> findByIdAndDeletedAtIsNull(long id);

    /** 注销级联用：某人上传的全部未删照片（NFR-8 / D1/D2）。 */
    List<PlacePhoto> findByUploaderIdAndDeletedAtIsNull(long uploaderId);
}

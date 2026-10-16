package com.tailtopia.admin.usermgmt.repository;

import com.tailtopia.admin.usermgmt.domain.VideoTrustedPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 视频免审名单（专题线 video Story 1.1）。 */
public interface VideoTrustedPublisherRepository extends JpaRepository<VideoTrustedPublisher, Long> {

    /** 名单列表：加入时间倒序（同刻按 user_id 倒序定序）。 */
    Page<VideoTrustedPublisher> findAllByOrderByCreatedAtDescUserIdDesc(Pageable pageable);

    /**
     * 原子加入：已存在则不插（{@code ON CONFLICT DO NOTHING}）。返回实际插入行数（1 = 新加入，0 = 本已在名单）。
     *
     * <p>🔴 不要改回「existsById → save」：并发双击时两边都判不存在，后提交的一方撞主键 → 500（code-review 2026-10-16）。
     */
    @Modifying
    @Query(value = "INSERT INTO video_trusted_publishers (user_id, created_by_admin_id) VALUES (:userId, :adminId)"
            + " ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("adminId") long adminId);

    /** 原子移出：返回实际删除行数（1 = 已移出，0 = 本不在名单）。并发重复移出只有一方得到 1。 */
    @Modifying
    @Query(value = "DELETE FROM video_trusted_publishers WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") long userId);
}

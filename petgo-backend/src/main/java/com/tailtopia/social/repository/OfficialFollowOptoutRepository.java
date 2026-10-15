package com.tailtopia.social.repository;

import com.tailtopia.social.domain.UserFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 官方号取关记录（V1.3.4 following 流）：用户主动取关官方号后写一行，之后不再被自动关注。
 * 表无实体，原生 SQL 读写；写操作必须在事务内调用。
 */
public interface OfficialFollowOptoutRepository extends JpaRepository<UserFollow, UserFollow.Key> {

    @Modifying
    @Query(value = """
            INSERT INTO official_follow_optouts (user_id, official_id, created_at)
            VALUES (:userId, :officialId, now())
            ON CONFLICT (user_id, official_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") long userId, @Param("officialId") long officialId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM official_follow_optouts "
            + "WHERE user_id = :userId AND official_id = :officialId)", nativeQuery = true)
    boolean exists(@Param("userId") long userId, @Param("officialId") long officialId);
}

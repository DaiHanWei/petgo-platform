package com.tailtopia.social.repository;

import com.tailtopia.social.domain.PetFollow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 追更宠物关系读写（V1.4.2 social-topic · Story 1.1 · D-1 / D-8 / D-11）。
 *
 * <p>⚠️ <b>本仓储只允许 {@code com.tailtopia.social} 模块内部引用</b>；
 * {@code content} / {@code profile} / {@code auth} / {@code notify} 一律经 {@code social.read.PetFollowReader}。
 *
 * <p>单只宠物计数 ±1 走 {@code PetProfileRepository#addFollowerCount}；只有下面两个「删行 + 批量扣计数」
 * （拉黑联动 / 注销清理）为了原子性写成一条 CTE，在这里直接 UPDATE {@code pet_profiles}——
 * social 侧碰 {@code pet_profiles} 的 SQL 只有这两处。
 */
public interface PetFollowRepository extends JpaRepository<PetFollow, PetFollow.Key> {

    /**
     * 幂等追更：已追更则什么都不做（不刷新时间戳），返回实际插入行数（0 / 1）。
     *
     * <p>⚠️ 必须用 {@code ON CONFLICT DO NOTHING}，不要改成「save + catch 唯一约束异常」——
     * 约束异常穿出 repo 代理时共享事务已被标记 rollback-only，外层提交必 500。
     * 调用方<b>只在返回 1 时</b>改计数、发事件，并发双击只算一次。
     */
    @Modifying
    @Query(value = """
            INSERT INTO pet_follows (follower_id, pet_profile_id, created_at)
            VALUES (:followerId, :petId, now())
            ON CONFLICT (follower_id, pet_profile_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("followerId") long followerId, @Param("petId") long petId);

    /** 取消追更：返回实际删除行数（0 / 1）。调用方只在返回 1 时扣计数。 */
    @Modifying
    @Query(value = "DELETE FROM pet_follows WHERE follower_id = :followerId AND pet_profile_id = :petId",
            nativeQuery = true)
    int deleteRelation(@Param("followerId") long followerId, @Param("petId") long petId);

    boolean existsByFollowerIdAndPetProfileId(long followerId, long petProfileId);

    /**
     * 某人自 {@code since} 起新建、<b>且仍存在</b>的追更行数（每日额度两表合计的一半，D-8）。
     * 走 {@code idx_pet_follows_follower_created}。
     */
    @Query(value = "SELECT count(*) FROM pet_follows WHERE follower_id = :followerId AND created_at >= :since",
            nativeQuery = true)
    long countCreatedSince(@Param("followerId") long followerId, @Param("since") Instant since);

    /** 某人追更的宠物数（列表首页 / 主页「Hewan N」）。 */
    long countByFollowerId(long followerId);

    /**
     * viewer 在这批宠物里追更了哪些 —— 一条 SQL 解决整批（禁止逐个 exists）。
     * 调用方保证 {@code petIds} 非空（{@code IN ()} 在 Postgres 上是语法错误）。
     */
    @Query("SELECT f.petProfileId FROM PetFollow f WHERE f.followerId = :viewerId AND f.petProfileId IN :petIds")
    List<Long> findFollowedPetIdsAmong(@Param("viewerId") long viewerId,
            @Param("petIds") Collection<Long> petIds);

    /**
     * 某人追更的宠物一页：keyset {@code created_at DESC, pet_profile_id DESC}，行值比较
     * （同 {@code UserFollowRepository#findFollowingPage}，走 {@code idx_pet_follows_follower_created}）。
     * 调用方传 {@code limit + 1} 判 hasMore。
     */
    @Query(value = """
            SELECT * FROM pet_follows
             WHERE follower_id = :followerId AND (created_at, pet_profile_id) < (:cursorTs, :cursorId)
             ORDER BY created_at DESC, pet_profile_id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<PetFollow> findFollowingPage(@Param("followerId") long followerId,
            @Param("cursorTs") Instant cursorTs, @Param("cursorId") long cursorId, @Param("limit") int limit);

    /**
     * 拉黑联动（D-11）：删除 {@code holder} 对 {@code owner} 名下<b>所有宠物</b>的追更行，并对每只被删的宠物
     * {@code follower_count − 1}。一条语句、原子（被删的行才扣，不会多扣）；返回被扣减的宠物只数。
     *
     * <p>{@code owner → holder} 宠物的追更不在此列（只删一个方向）。
     */
    @Modifying
    @Query(value = """
            WITH d AS (
                DELETE FROM pet_follows f USING pet_profiles p
                 WHERE f.follower_id = :holderId AND f.pet_profile_id = p.id AND p.owner_id = :ownerId
                RETURNING f.pet_profile_id
            )
            UPDATE pet_profiles SET follower_count = GREATEST(follower_count - 1, 0)
             WHERE id IN (SELECT pet_profile_id FROM d)
            """, nativeQuery = true)
    int deleteByFollowerAndOwner(@Param("holderId") long holderId, @Param("ownerId") long ownerId);

    /**
     * 注销清理（D-11 / AC7.2）：删除 {@code userId} 的全部追更行，并对他追更的每只宠物 {@code follower_count − 1}。
     * 一条语句、原子；幂等可重跑（第二次删 0 行、扣 0 只）。返回被扣减的宠物只数。
     *
     * <p>⚠️ 不能依赖 {@code fk_pet_follows_follower} 的 CASCADE：注销只匿名化 users 行，CASCADE 永不触发。
     */
    @Modifying
    @Query(value = """
            WITH d AS (
                DELETE FROM pet_follows WHERE follower_id = :userId
                RETURNING pet_profile_id
            )
            UPDATE pet_profiles SET follower_count = GREATEST(follower_count - 1, 0)
             WHERE id IN (SELECT pet_profile_id FROM d)
            """, nativeQuery = true)
    int purgeFollower(@Param("userId") long userId);
}

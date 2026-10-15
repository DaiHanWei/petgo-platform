package com.tailtopia.notify.ops.repository;

import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营通知发送活动仓储（V1.3.4 Story 6.1）。状态机条件更新方法由 Story 6.2 追加。
 *
 * <p>🔴 <b>状态迁移一律条件更新</b>（{@code WHERE ... AND status = ...}），返回影响行数：多实例 / 重复投递 /
 * 超时扫描并发时只有一方生效（epics 6.2「只有一个实例执行」）。JPQL 批量更新绕过 {@code @PreUpdate}，
 * 故每条都显式写 {@code updatedAt}。每个方法自带短事务（dispatcher 本身不加事务）。
 */
public interface OpsNotificationCampaignRepository extends JpaRepository<OpsNotificationCampaign, Long> {

    Optional<OpsNotificationCampaign> findByPublicToken(String publicToken);

    /** 后台发送记录列表（V1.3.4 AB-31）：新建的在前，同刻按 id 倒序保证翻页稳定。 */
    Page<OpsNotificationCampaign> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** 通知中心列表一页一次批量回查（不 N+1）。 */
    List<OpsNotificationCampaign> findByPublicTokenIn(Collection<String> publicTokens);

    /** 抢占：PENDING 且已到点 → SENDING。返回 0 = 已被抢走 / 已取消 / 未到点。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING,
                   c.startedAt = :now, c.lastProgressAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.PENDING
               and (c.scheduledAt is null or c.scheduledAt <= :now)
            """)
    int claimForSending(@Param("id") long id, @Param("now") Instant now);

    /** 通知中心写入成功人数累加 + 刷进展。返回 0 = 已不在 SENDING（被超时扫描改掉），须停止。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.recipientCount = c.recipientCount + :n, c.lastProgressAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
            """)
    int addRecipients(@Param("id") long id, @Param("n") int n, @Param("now") Instant now);

    /** 只刷进展。返回 0 = 已不在 SENDING，须停止。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.lastProgressAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
            """)
    int touchProgress(@Param("id") long id, @Param("now") Instant now);

    /** 失败人数累加（单位：人）+ 刷进展。返回 0 = 已不在 SENDING，须停止。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.failedCount = c.failedCount + :n, c.lastProgressAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
            """)
    int addFailed(@Param("id") long id, @Param("n") int n, @Param("now") Instant now);

    /** IDS 模式无效 id 个数（子表条数 − 命中人数）+ 刷进展。返回 0 = 已不在 SENDING，须停止。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.skippedCount = :n, c.lastProgressAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
            """)
    int setSkipped(@Param("id") long id, @Param("n") int n, @Param("now") Instant now);

    /** 收尾：SENDING → DONE / PARTIAL_FAILED。已被超时扫描改掉的不覆盖。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.status = :finalStatus, c.finishedAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
            """)
    int finish(@Param("id") long id, @Param("finalStatus") OpsCampaignStatus finalStatus,
            @Param("now") Instant now);

    /** 超时扫描候选：SENDING 且 {@code last_progress_at < threshold}（threshold = now − 15min）。 */
    @Query("""
            select c.id from OpsNotificationCampaign c
             where c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
               and c.lastProgressAt < :threshold
             order by c.id
            """)
    List<Long> findStalledIds(@Param("threshold") Instant threshold);

    /**
     * 无进展超时：SENDING 且 {@code last_progress_at < threshold} → PARTIAL_FAILED，<b>不自动重发</b>（D-20）。
     * 条件里重判阈值：查出候选后发送线程又刷了进展的不改。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.PARTIAL_FAILED,
                   c.finishedAt = :now, c.updatedAt = :now
             where c.id = :id
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.SENDING
               and c.lastProgressAt < :threshold
            """)
    int markStalled(@Param("id") long id, @Param("threshold") Instant threshold, @Param("now") Instant now);

    /** 到期扫描：PENDING 且（立即 或 已到点），先到先发，最多 {@code limit} 个。 */
    @Query(value = """
            SELECT id FROM ops_notification_campaigns
             WHERE status = 'PENDING' AND (scheduled_at IS NULL OR scheduled_at <= :now)
             ORDER BY COALESCE(scheduled_at, created_at), id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    /** 取消：仅 PENDING 可取消。返回 0 = 不存在或已非 PENDING。 */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OpsNotificationCampaign c
               set c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.CANCELED,
                   c.canceledAt = :now, c.updatedAt = :now
             where c.publicToken = :token
               and c.status = com.tailtopia.notify.ops.domain.OpsCampaignStatus.PENDING
            """)
    int cancelIfPending(@Param("token") String token, @Param("now") Instant now);
}

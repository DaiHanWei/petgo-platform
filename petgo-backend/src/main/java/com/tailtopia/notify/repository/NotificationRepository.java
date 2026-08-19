package com.tailtopia.notify.repository;

import com.tailtopia.notify.domain.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 通知持久层（Story 6.1）。6.6 通知中心按收件人倒序拉取（游标分页）；按 token 回查/标记已读；未读回算。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 游标分页：某用户在游标 {@code (beforeTs, beforeId)} 之前的通知倒序。
     *
     * <p>🔴🔴 <b>游标必须是 {@code (created_at, id)} 复合的，不能只有 {@code created_at}</b>
     * （2026-08-18 修，见 {@code sprint-status action_items: NOTIFY-CURSOR-TIE}）。
     *
     * <p>原实现是 {@code created_at < cursor} 且游标截断到毫秒，于是<b>同一毫秒内有 ≥2 条通知、
     * 分页边界又正好落在中间时，那一毫秒里的记录会被整批跳过</b> —— 用户永久看不到那几条。
     * 一毫秒内写入多条通知在生产上完全正常（一次批量触达就是）。
     *
     * <p>顺带修掉的第二个问题：原 {@code ORDER BY created_at DESC} 对同刻记录<b>没有确定顺序</b>，
     * 即便不撞游标，翻页时同一条也可能重复出现或消失。加 {@code id DESC} 后全序唯一。
     *
     * <p>⚠️ {@code beforeTs} 必须与库里存的值<b>精确相等</b>才能命中第二个分支 ——
     * 编码游标时按微秒（Postgres {@code timestamptz} 的精度）取，见
     * {@code NotificationCenterService#encodeCursor}。
     */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientUserId = :userId
              AND (n.createdAt < :beforeTs
                   OR (n.createdAt = :beforeTs AND n.id < :beforeId))
            ORDER BY n.createdAt DESC, n.id DESC
            """)
    List<Notification> findPageBefore(@Param("userId") long userId,
            @Param("beforeTs") Instant beforeTs, @Param("beforeId") long beforeId,
            Pageable pageable);

    /** 某用户全部通知（倒序，全序唯一）。⚠️ 无分页，只给测试与小规模内部核对用。 */
    List<Notification> findByRecipientUserIdOrderByCreatedAtDescIdDesc(long recipientUserId);

    Optional<Notification> findByDeepLinkTokenAndRecipientUserId(String deepLinkToken, long recipientUserId);

    long countByRecipientUserIdAndReadIsFalse(long recipientUserId);

    List<Notification> findByRecipientUserIdAndReadIsFalse(long recipientUserId);

    /** Story 7.3：注销级联删除该用户全部通知（纯个人数据，物理删除）。 */
    @org.springframework.transaction.annotation.Transactional
    void deleteByRecipientUserId(long recipientUserId);
}

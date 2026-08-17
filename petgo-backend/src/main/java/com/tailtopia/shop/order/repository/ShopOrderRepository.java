package com.tailtopia.shop.order.repository;

import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 电商订单仓储（Story 3.2）。 */
public interface ShopOrderRepository extends JpaRepository<ShopOrder, Long> {

    /**
     * 🔴 <b>按 (token, userId) 双条件查</b> —— 与地址簿同理：
     * 让「不是你的」和「不存在」在代码路径上就是同一件事，天然 404，不泄露 token 是否存在。
     */
    Optional<ShopOrder> findByPublicTokenAndUserId(String publicToken, long userId);

    Optional<ShopOrder> findByPublicToken(String publicToken);

    List<ShopOrder> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    /** 订单中心游标分页用（Story 3.9：与虚拟商品订单跨源归并，取 createdAt < cursor 的一页）。 */
    List<ShopOrder> findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(long userId, Instant before,
            Pageable pageable);

    /** 按当前支付意图 token 回找订单（到账事件用，Story 3.8）。 */
    Optional<ShopOrder> findByPaymentIntentToken(String paymentIntentToken);

    /**
     * 已过支付窗仍待支付的订单（Story 3.8 超时扫描，AD-8）。
     *
     * <p>🔴 {@code expires_at IS NOT NULL} 由字段本身保证：本列上线前的历史订单没有窗，
     * 不该被"超时取消"。
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.status = :status AND o.expiresAt IS NOT NULL AND o.expiresAt < :now
            ORDER BY o.expiresAt ASC
            """)
    List<ShopOrder> findOverdue(@Param("status") ShopOrderStatus status,
            @Param("now") Instant now, Pageable pageable);

    default List<ShopOrder> findOverduePendingPayment(Instant now, Pageable pageable) {
        return findOverdue(ShopOrderStatus.PENDING_PAYMENT, now, pageable);
    }

    /** 后台订单列表（Story 4.2，按下单时间倒序）。 */
    List<ShopOrder> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** 后台按状态筛选（Story 4.2 发货队列 / Story 4.3 状态筛选）。 */
    List<ShopOrder> findByStatusOrderByCreatedAtDescIdDesc(ShopOrderStatus status,
            Pageable pageable);

    /**
     * 后台组合筛选（Story 4.3，AB-11A）：状态 + 时间范围，任一为 null 即不参与筛选。
     *
     * <p>🔒 <b>本查询刻意不含电话</b> —— 按电话反查是独立能力（独立权限位 + 每次审计），
     * 混进通用筛选就等于让每个能看订单的人都顺手拥有了它。
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE (:status IS NULL OR o.status = :status)
              AND (CAST(:from AS timestamp) IS NULL OR o.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR o.createdAt < :to)
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<ShopOrder> search(@Param("status") ShopOrderStatus status, @Param("from") Instant from,
            @Param("to") Instant to, Pageable pageable);

    /**
     * 🔒 <b>按归一化后的收件人电话后缀匹配</b>（Story 4.3，AB-11A，NFR-11）。
     *
     * <p>得益于 C-15 的 E.164 归一化，{@code 08123…} / {@code 8123…} / {@code +62 812-3…}
     * 三种输入形式指向同一个存储值，都能被搜到。不归一则会搜不全 ——
     * 而「搜不全」在客服场景里表现为「系统说你没下过单」，比搜不到更糟。
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.shipReceiverPhone LIKE :suffixPattern
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<ShopOrder> searchByPhoneSuffix(@Param("suffixPattern") String suffixPattern,
            Pageable pageable);

    /**
     * 已签收且送达时间在 {@code since} 之后的订单（Story 6.3 复购日扫的输入）。
     *
     * <p>🔴 只看已签收的：粮是从送达那天开始吃的（S-14 修正②）。
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.deliveredAt IS NOT NULL AND o.deliveredAt >= :since
              AND o.status IN (com.tailtopia.shop.order.domain.ShopOrderStatus.DELIVERED,
                               com.tailtopia.shop.order.domain.ShopOrderStatus.COMPLETED)
            ORDER BY o.deliveredAt DESC
            """)
    List<ShopOrder> findDeliveredSince(@Param("since") Instant since);

    // ---------- Story 4.1 履约段自动推进 ----------

    /**
     * 发货起已超 M 日仍无任何送达标记的订单（SPEC-2 出口③）。
     *
     * <p>🔴 {@code shippedAt IS NOT NULL} 不是冗余条件：本列上线前若已有 {@code SHIPPED} 订单
     * （历史数据 / 手工改库），它们没有起算点，不该被"自动送达"。
     */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.status = com.tailtopia.shop.order.domain.ShopOrderStatus.SHIPPED
              AND o.shippedAt IS NOT NULL AND o.shippedAt < :threshold
            ORDER BY o.shippedAt ASC
            """)
    List<ShopOrder> findAutoDeliverDue(@Param("threshold") Instant threshold, Pageable pageable);

    /** 送达起已超 7 日用户仍未确认的订单（FR-102 自动完成）。 */
    @Query("""
            SELECT o FROM ShopOrder o
            WHERE o.status = com.tailtopia.shop.order.domain.ShopOrderStatus.DELIVERED
              AND o.deliveredAt IS NOT NULL AND o.deliveredAt < :threshold
            ORDER BY o.deliveredAt ASC
            """)
    List<ShopOrder> findAutoCompleteDue(@Param("threshold") Instant threshold, Pageable pageable);
}

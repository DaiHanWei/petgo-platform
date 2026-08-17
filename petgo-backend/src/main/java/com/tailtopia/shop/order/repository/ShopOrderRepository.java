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
}

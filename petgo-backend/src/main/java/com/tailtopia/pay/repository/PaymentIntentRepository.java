package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 支付意图仓储（Story 1.1）。回调/轮询按 {@code public_token}（order_id）与 {@code gateway_ref} 定位；
 * 二者均唯一约束，去重库级兜底。
 */
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPublicToken(String publicToken);

    Optional<PaymentIntent> findByGatewayRef(String gatewayRef);

    /** 收款对账（轮询通道）：按状态取一批（未终态意图对账用）。 */
    List<PaymentIntent> findByStatus(PaymentStatus status, Pageable pageable);

    /** 后台支付记录通用查询（Story 9.6，AB-8E）：某用户全部支付意图倒序（四类 purpose 统一）。 */
    List<PaymentIntent> findByUserIdOrderByCreatedAtDesc(long userId);

    /**
     * 订单中心游标分页（Story 5.1）：本人 充值(PAWCOIN_TOPUP) 订单，游标 {@code (beforeTs, beforeId)} 之后的一页，
     * 排序 {@code created_at DESC, id DESC}。
     *
     * <p>🔴🔴 <b>游标必须是 {@code (created_at, id)} 复合的</b>（2026-08-18 修，
     * {@code action_items: ORDER-CENTER-CURSOR-TIE}）。原来是 {@code created_at < cursor}
     * 且游标截断到毫秒 —— 落在同一毫秒里的订单会在分页边界被<b>整批跳过</b>，用户再也看不到。
     *
     * <p>⚠️ {@code beforeId} 由 {@code OrderCenterService} 按<b>源的先后位</b>给哨兵值：
     * 排在游标源之前的源传 {@code Long.MIN_VALUE}（同刻组已发完 → 退化为严格 {@code <}），
     * 之后的源传 {@code Long.MAX_VALUE}（同刻组还没发 → 等价 {@code <=}），
     * 游标自己那个源传真实 id。这样<b>一条查询覆盖三种边界</b>，
     * 且排序键与归并层的全序<b>逐字一致</b> —— 两处不一致就是漏单的来源。
     */
    @Query("""
            SELECT o FROM PaymentIntent o
            WHERE o.userId = :userId
              AND o.purpose = :purpose AND o.status = :status
              AND (o.createdAt < :beforeTs
                   OR (o.createdAt = :beforeTs AND o.id < :beforeId))
            ORDER BY o.createdAt DESC, o.id DESC
            """)
    List<PaymentIntent> findOrderCenterPageBefore(@Param("userId") long userId,
            @Param("purpose") PaymentPurpose purpose,
            @Param("status") PaymentStatus status, @Param("beforeTs") Instant beforeTs,
            @Param("beforeId") long beforeId, Pageable pageable);

    /** 用户主动取消联动（问诊 QRIS）：某用户某 purpose 最新一笔指定状态意图（置 FAILED 用）。 */
    Optional<PaymentIntent> findFirstByUserIdAndPurposeAndStatusOrderByCreatedAtDesc(
            long userId, PaymentPurpose purpose, PaymentStatus status);

    /** 复用同档位未过期 PENDING 充值（V85，D-b）：同 (user, purpose, channel, amount) 最新一笔 PENDING。 */
    Optional<PaymentIntent> findFirstByUserIdAndPurposeAndChannelAndAmountAndStatusOrderByCreatedAtDesc(
            long userId, PaymentPurpose purpose,
            com.tailtopia.pay.domain.PayChannel channel, long amount, PaymentStatus status);

    /** 定时过期扫描（V85）：PENDING 且 expires_at < now 的一批（充值 60min 窗超时置 EXPIRED）。 */
    List<PaymentIntent> findByStatusAndExpiresAtBefore(PaymentStatus status, Instant now, Pageable pageable);
}

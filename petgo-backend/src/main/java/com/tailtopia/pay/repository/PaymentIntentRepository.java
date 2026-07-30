package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** 订单中心游标分页（Story 5.1）：本人 充值(PAWCOIN_TOPUP) PAID 意图 created_at < cursor 倒序（充值凭证）。 */
    List<PaymentIntent> findByUserIdAndPurposeAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, PaymentPurpose purpose, PaymentStatus status, Instant cursor, Pageable pageable);

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

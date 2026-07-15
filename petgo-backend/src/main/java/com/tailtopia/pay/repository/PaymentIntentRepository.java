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

    /** 后台支付记录通用查询（Story 9.6，AB-8E）：某用户全部支付意图倒序（四类 purpose 统一）。 */
    List<PaymentIntent> findByUserIdOrderByCreatedAtDesc(long userId);

    /** 订单中心游标分页（Story 5.1）：本人 充值(PAWCOIN_TOPUP) PAID 意图 created_at < cursor 倒序（充值凭证）。 */
    List<PaymentIntent> findByUserIdAndPurposeAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
            long userId, PaymentPurpose purpose, PaymentStatus status, Instant cursor, Pageable pageable);
}

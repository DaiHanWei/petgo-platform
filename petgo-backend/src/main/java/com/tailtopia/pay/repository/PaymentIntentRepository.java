package com.tailtopia.pay.repository;

import com.tailtopia.pay.domain.PaymentIntent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 支付意图仓储（Story 1.1）。回调/轮询按 {@code public_token}（order_id）与 {@code gateway_ref} 定位；
 * 二者均唯一约束，去重库级兜底。
 */
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPublicToken(String publicToken);

    Optional<PaymentIntent> findByGatewayRef(String gatewayRef);
}

package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.IdCardHdPurchase;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCardHdPurchaseRepository extends JpaRepository<IdCardHdPurchase, Long> {

    /** 是否已购买（老永久解锁语义，仅保留兼容旧单卡端点 getMyIdCard/generateSerial）。 */
    boolean existsByUserId(long userId);

    /** QRIS 下单去重（同一 intent 重复发起不双建 attempt 行）。 */
    boolean existsByPaymentIntentId(long paymentIntentId);

    /** 回调按 intentId 反查 attempt 行 → 取 card_id 翻转解锁（Story 6-7）。 */
    Optional<IdCardHdPurchase> findFirstByPaymentIntentId(long paymentIntentId);
}

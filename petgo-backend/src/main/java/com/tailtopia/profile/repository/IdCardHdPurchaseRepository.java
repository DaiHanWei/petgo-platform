package com.tailtopia.profile.repository;

import com.tailtopia.profile.domain.IdCardHdPurchase;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCardHdPurchaseRepository extends JpaRepository<IdCardHdPurchase, Long> {

    /** 是否已购买（老永久解锁语义，仅保留兼容旧单卡端点 getMyIdCard/generateSerial）。 */
    /**
     * ⚠️ 只表示「有行」—— 按卡 QRIS 出码前就写了 attempt 行（未付款、甚至网关下单失败也留着），
     * <b>不能</b>拿来判断「已购买」。判断已购买一律用 {@link #existsPaidByUserId}（2026-09-25 code review #3）。
     */
    boolean existsByUserId(long userId);

    /**
     * 该用户是否<b>真的付过</b>高清图：无支付意图的行（PawCoin 即时扣款成功后才写）
     * 或其支付意图已 {@code PAID}。按卡 QRIS 的未付 / 失败 attempt 行不算。
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM id_card_hd_purchases h
                LEFT JOIN payment_intents pi ON pi.id = h.payment_intent_id
                WHERE h.user_id = :userId
                  AND (h.payment_intent_id IS NULL OR pi.status = 'PAID'))
            """, nativeQuery = true)
    boolean existsPaidByUserId(@org.springframework.data.repository.query.Param("userId") long userId);

    /** QRIS 下单去重（同一 intent 重复发起不双建 attempt 行）。 */
    boolean existsByPaymentIntentId(long paymentIntentId);

    /** 回调按 intentId 反查 attempt 行 → 取 card_id 翻转解锁（Story 6-7）。 */
    Optional<IdCardHdPurchase> findFirstByPaymentIntentId(long paymentIntentId);
}

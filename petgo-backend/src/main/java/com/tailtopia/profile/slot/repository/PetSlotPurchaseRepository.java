package com.tailtopia.profile.slot.repository;

import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 名额购买行（V1.4.0 multi-pet Story 2.2）。 */
public interface PetSlotPurchaseRepository extends JpaRepository<PetSlotPurchase, Long> {

    Optional<PetSlotPurchase> findByPaymentIntentId(long paymentIntentId);

    Optional<PetSlotPurchase> findByIdemKey(String idemKey);

    /** App 轮询寻址：按对外 token + 归属取（非本人 = 查不到 → 404）。 */
    Optional<PetSlotPurchase> findByPublicTokenAndUserId(String publicToken, long userId);

    /** 同键重开时找旧 PENDING 行（调用方再按 {@code idem_key} 精确匹配）。 */
    List<PetSlotPurchase> findByUserIdAndStatus(long userId, PetSlotPurchaseStatus status);

    /**
     * 仍可能到账的新租单会新增的名额数：该用户未结算的 RENT 购买，其意图仍 PENDING 且付款窗未过（排除 {@code excludeIntentId}）。
     * 下单时与当前名额一起算可租数，防「QRIS 租 4 未付 + PawCoin 再租 4」到账后名额超 5（code review #2）。
     */
    @Query(value = """
            SELECT COALESCE(SUM(p.quantity), 0) FROM pet_slot_purchases p
            JOIN payment_intents i ON i.id = p.payment_intent_id
            WHERE p.user_id = :userId AND p.kind = 'RENT'
              AND p.status NOT IN ('PAID', 'ORPHAN_PAID', 'OVERPAID')
              AND i.status = 'PENDING' AND (i.expires_at IS NULL OR i.expires_at > :now)
              AND p.payment_intent_id <> :excludeIntentId
            """, nativeQuery = true)
    long sumOpenRentSlots(@Param("userId") long userId, @Param("now") Instant now,
            @Param("excludeIntentId") long excludeIntentId);

    /**
     * 仍可能到账的续租单里，到账时会因所选名额已过期而改为新建的名额数（所选名额已非 ACTIVE，或在付款窗结束前到期）。
     * 口径同 {@link #sumOpenRentSlots}。
     */
    @Query(value = """
            SELECT count(*) FROM pet_slot_purchases p
            JOIN payment_intents i ON i.id = p.payment_intent_id
            JOIN pet_slot_rentals r ON r.id = ANY (p.rental_ids)
            WHERE p.user_id = :userId AND p.kind = 'RENEW'
              AND p.status NOT IN ('PAID', 'ORPHAN_PAID', 'OVERPAID')
              AND i.status = 'PENDING' AND (i.expires_at IS NULL OR i.expires_at > :now)
              AND p.payment_intent_id <> :excludeIntentId
              AND (r.status <> 'ACTIVE' OR i.expires_at IS NULL OR r.expires_at <= i.expires_at)
            """, nativeQuery = true)
    long countOpenRenewConversions(@Param("userId") long userId, @Param("now") Instant now,
            @Param("excludeIntentId") long excludeIntentId);
}

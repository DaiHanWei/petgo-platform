package com.tailtopia.starterpack.repository;

import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackPurchaseStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 礼包购买仓储（V1.5.0 gift-pack Story 4-2）。 */
public interface StarterPackPurchaseRepository extends JpaRepository<StarterPackPurchase, Long> {

    Optional<StarterPackPurchase> findByIdemKey(String idemKey);

    Optional<StarterPackPurchase> findByPaymentIntentId(long paymentIntentId);

    /** App 寻址：按对外 token + 归属取（非本人 = 查不到 → 404）。 */
    Optional<StarterPackPurchase> findByPublicTokenAndUserId(String publicToken, long userId);

    /** 该用户当前 PENDING 单（库级 {@code uq_starter_pack_purchases_one_pending} 保证至多一笔）。 */
    Optional<StarterPackPurchase> findFirstByUserIdAndStatus(long userId, StarterPackPurchaseStatus status);

    /** 最近一笔 PAID（冷却判据，走 {@code ix_starter_pack_purchases_user_paid}）。 */
    Optional<StarterPackPurchase> findFirstByUserIdAndStatusOrderByPaidAtDesc(long userId,
            StarterPackPurchaseStatus status);

    long countByUserIdAndStatus(long userId, StarterPackPurchaseStatus status);

    Optional<StarterPackPurchase> findByPublicToken(String publicToken);

    /**
     * 订单中心第 6 源（V1.5.0 gift-pack Story 4-4 · D-26）：PAID 全部 + 未过窗 PENDING；与 {@code KeepsakePurchaseRepository
     * .findOrderCenterPageBefore} <b>同形</b>（排序键 {@code created_at DESC, id DESC}，同刻组靠 {@code beforeId}）。
     */
    @Query("""
            SELECT p FROM StarterPackPurchase p
            WHERE p.userId = :userId
              AND (p.status = com.tailtopia.starterpack.domain.StarterPackPurchaseStatus.PAID
                   OR (p.status = com.tailtopia.starterpack.domain.StarterPackPurchaseStatus.PENDING
                       AND p.payExpiresAt > :now))
              AND (p.createdAt < :beforeTs
                   OR (p.createdAt = :beforeTs AND p.id < :beforeId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<StarterPackPurchase> findOrderCenterPageBefore(@Param("userId") long userId, @Param("now") Instant now,
            @Param("beforeTs") Instant beforeTs, @Param("beforeId") long beforeId, Pageable page);

    /** 发放锁行（D-24：幂等判 {@code fulfilled_at} 前先锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM StarterPackPurchase p WHERE p.id = :id")
    Optional<StarterPackPurchase> findByIdForUpdate(@Param("id") long id);

    /**
     * 补发候选（走 {@code ix_starter_pack_purchases_unfulfilled}）：已付未发放、到账超过宽限且仍在补跑窗口内
     * （{@code paid_at ∈ [after, before)}；超窗的不再自动补跑，交人工，4-2 复审 #5）。
     */
    @Query("SELECT p.id FROM StarterPackPurchase p WHERE p.status = com.tailtopia.starterpack.domain"
            + ".StarterPackPurchaseStatus.PAID AND p.fulfilledAt IS NULL AND p.paidAt < :before"
            + " AND p.paidAt >= :after ORDER BY p.paidAt")
    List<Long> findUnfulfilledPaidBetween(@Param("after") Instant after, @Param("before") Instant before,
            Pageable page);
}

package com.tailtopia.profile.domain;

import com.tailtopia.pay.domain.PayChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 身份证高清图一次性永久购买记录（Story 6.3，FR-49D）。一账号至多一行（{@code UNIQUE(user_id)}），
 * 存在即「已永久解锁」。删档/注销后仍在（{@code pet_profile_id} ON DELETE SET NULL）=永久。
 */
@Entity
@Table(name = "id_card_hd_purchases")
public class IdCardHdPurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pet_profile_id")
    private Long petProfileId;

    /** 购买绑定的卡快照 id（Story 6-7，决策①）：一卡一次购买。老数据回填后非空。 */
    @Column(name = "card_id")
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_channel", nullable = false, length = 16)
    private PayChannel payChannel;

    @Column(name = "payment_intent_id")
    private Long paymentIntentId;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private Instant purchasedAt;

    protected IdCardHdPurchase() {
    }

    public static IdCardHdPurchase of(long userId, Long petProfileId, Long cardId,
            PayChannel payChannel, Long paymentIntentId) {
        IdCardHdPurchase p = new IdCardHdPurchase();
        p.userId = userId;
        p.petProfileId = petProfileId;
        p.cardId = cardId;
        p.payChannel = payChannel;
        p.paymentIntentId = paymentIntentId;
        return p;
    }

    @PrePersist
    void onCreate() {
        this.purchasedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetProfileId() {
        return petProfileId;
    }

    public Long getCardId() {
        return cardId;
    }

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public Long getPaymentIntentId() {
        return paymentIntentId;
    }

    public Instant getPurchasedAt() {
        return purchasedAt;
    }
}

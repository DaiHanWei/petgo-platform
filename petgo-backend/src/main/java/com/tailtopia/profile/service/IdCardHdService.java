package com.tailtopia.profile.service;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.IdCardHdPurchase;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.HdPurchaseResponse;
import com.tailtopia.profile.repository.IdCardHdPurchaseRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 身份证高清图一次性永久购买编排（Story 6.3，FR-49D）。范式照抄 {@link com.tailtopia.triage.service.AiUnlockService}
 * （AI 一次性解锁）——一次性、双渠道、多层幂等不重复扣费；ID_HD 更简单（无免费额度、无红色态短路）。
 *
 * <p><b>三层幂等不重复扣费</b>：① 入口 {@code existsByUserId} 短路（在任何扣费之前）② PawCoin debit 幂等键
 * {@code id-hd:{petProfileId}} ③ createIntent 幂等键（同键取回既有 intent，不双建）④ {@code UNIQUE(user_id)} 库级兜底。
 */
@Service
public class IdCardHdService {

    private static final Logger log = LoggerFactory.getLogger(IdCardHdService.class);
    private static final String REF_TYPE = "ID_HD";
    private static final String CURRENCY = "IDR";
    // 付款窗 60min（与充值一致，< GemPay QR 有效期）：超窗二维码过期，重开出新码（bug 20260722）。
    private static final java.time.Duration PAY_WINDOW = java.time.Duration.ofMinutes(60);

    private final PetProfileRepository profiles;
    private final IdCardHdPurchaseRepository purchases;
    private final PawCoinWalletService wallet;
    private final PaymentIntentService paymentIntents;
    private final com.tailtopia.config.service.PlatformConfigService platformConfig;
    private final PaymentGateway gateway;

    public IdCardHdService(PetProfileRepository profiles, IdCardHdPurchaseRepository purchases,
            PawCoinWalletService wallet, PaymentIntentService paymentIntents,
            com.tailtopia.config.service.PlatformConfigService platformConfig, PaymentGateway gateway) {
        this.profiles = profiles;
        this.purchases = purchases;
        this.wallet = wallet;
        this.paymentIntents = paymentIntents;
        this.platformConfig = platformConfig;
        this.gateway = gateway;
    }

    /** 当前用户是否已购买高清图（=已永久解锁）。 */
    @Transactional(readOnly = true)
    public boolean isUnlocked(long userId) {
        return purchases.existsByUserId(userId);
    }

    /**
     * 发起高清图购买。已购买 → 入口短路返回 UNLOCKED（不扣费不建行）。PawCoin 同步扣费+建购买行；
     * QRIS 建意图返回支付信息（到账由 {@link #completePurchase} 建行）。无档案 → 404。
     */
    @Transactional
    public HdPurchaseResponse purchase(long userId, PayChannel channel) {
        PetProfile pet = profiles.findByOwnerId(userId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        // 入口短路（任何扣费之前）：已购买 → 已解锁，绝不二次扣费/二次建行（AC1 幂等）。
        if (purchases.existsByUserId(userId)) {
            return HdPurchaseResponse.granted();
        }
        long petProfileId = pet.getId();
        // 成交价读后台可配定价（Story 9.2）；成交后不再变（一次性永久解锁）。
        long price = platformConfig.pricing().getIdHdDownloadPrice();
        return switch (channel) {
            case PAWCOIN -> {
                // 余额不足 → debit 抛冲突 → 整事务回滚（建行不发生）。幂等键稳定可重放。
                wallet.debit(userId, price, PawCoinTxnType.SPEND, REF_TYPE, petProfileId,
                        "id-hd:" + petProfileId);
                purchases.save(IdCardHdPurchase.of(userId, petProfileId, PayChannel.PAWCOIN, null));
                yield HdPurchaseResponse.granted();
            }
            case QRIS -> {
                // 幂等键 id-hd:{petProfileId}：60min 付款窗内重复发起取回既有 intent（不双建）；超窗则懒过期建新码
                // （bug：过期后重开支付页应出新二维码而非旧的）。窗口 < GemPay QR 有效期，窗口内码始终存活。
                PaymentIntentResponse intent = paymentIntents.createIntent(
                        userId, PaymentPurpose.ID_HD, PayChannel.QRIS, price, CURRENCY,
                        "id-hd:" + petProfileId, PAY_WINDOW);
                String payload = ensureCharge(intent, price, PayChannel.QRIS, PaymentPurpose.ID_HD);
                yield HdPurchaseResponse.paymentRequired(intent, payload);
            }
        };
    }

    /** 首次下单向网关取二维码载荷（照 PawCoinTopupService 范式）；幂等重放返回既有载荷（可重复支付复用）。 */
    private String ensureCharge(PaymentIntentResponse intent, long price, PayChannel channel,
            PaymentPurpose purpose) {
        PaymentIntent entity = paymentIntents.findByToken(intent.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        if (entity.getGatewayRef() == null) {
            ChargeResult charge = gateway.createCharge(new ChargeRequest(
                    entity.getPublicToken(), price, CURRENCY, channel.name(), purpose.name()));
            Map<String, Object> meta = new LinkedHashMap<>();
            if (charge.rawMeta() != null) {
                meta.putAll(charge.rawMeta());
            }
            if (charge.payload() != null) {
                meta.put("payload", charge.payload());
            }
            paymentIntents.attachCharge(entity.getPublicToken(), charge.gatewayRef(), meta);
            return charge.payload();
        }
        Map<String, Object> savedMeta = entity.getGatewayMeta();
        return savedMeta == null ? null : (String) savedMeta.get("payload");
    }

    /**
     * QRIS 到账后建购买行（Story 6.3 AC2）。供 {@code IdHdPaidHandler} 在 {@code applyCallback} 的<b>同一事务</b>内调
     * （{@code MANDATORY}，禁 AFTER_COMMIT）。幂等：已购买短路（回调/轮询重放不双建）。档案缺失时 pet_profile_id
     * 置 null 仍记（永久解锁成立）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completePurchase(long userId, PayChannel channel, long paymentIntentId) {
        if (purchases.existsByUserId(userId)) {
            return; // 幂等：回调/轮询重放
        }
        Long petProfileId = profiles.findByOwnerId(userId).map(PetProfile::getId).orElse(null);
        purchases.save(IdCardHdPurchase.of(userId, petProfileId, channel, paymentIntentId));
        log.info("身份证高清图现金到账解锁 user={} intent={}", userId, paymentIntentId);
    }
}

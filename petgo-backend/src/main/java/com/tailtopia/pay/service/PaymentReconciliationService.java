package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收款对账（轮询通道 · GemPay 对接）。回调缺失/迟到时，主动查网关收款结果并推进意图——喂给
 * {@link PaymentIntentService#applyCallback} <b>同一单一收口</b>，故与回调形成双通道且<b>只推进一次</b>
 * （Redis 前置 + {@code gateway_ref} 唯一 + 终态守卫）。
 *
 * <p><b>仅收款，不涉及 payout。</b> 系统/运维级能力（非 App 面向）——App 状态查询仍走
 * {@link PaymentIntentService#statusOf}（只读本地库，不刷网关，延续 Story 1.5 契约）。可由定时对账任务或运维
 * 端点消费本方法。
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentGateway gateway;
    private final PaymentIntentRepository intents;
    private final PaymentIntentService paymentIntentService;

    public PaymentReconciliationService(PaymentGateway gateway, PaymentIntentRepository intents,
            PaymentIntentService paymentIntentService) {
        this.gateway = gateway;
        this.intents = intents;
        this.paymentIntentService = paymentIntentService;
    }

    /**
     * 对账单笔：按 {@code public_token} 定位意图，若<b>未终态且已有网关订单号</b>则查网关收款结果，终态则经
     * 单一收口推进（PENDING 结果不推进）。返回对账后的当前状态。无匹配意图 → 404。
     */
    @Transactional
    public PaymentStatus reconcile(String publicToken) {
        PaymentIntent intent = intents.findByPublicToken(publicToken)
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        if (!intent.getStatus().isTerminal() && intent.getGatewayRef() != null) {
            gateway.queryCharge(intent.getGatewayRef())
                    .ifPresent(paymentIntentService::applyCallback);
        }
        // 重新读取（applyCallback 可能已推进）。
        return intents.findByPublicToken(publicToken)
                .map(PaymentIntent::getStatus)
                .orElse(intent.getStatus());
    }

    /**
     * 对账一批未终态意图（供定时任务/运维批量调用）。逐笔独立事务语义由 {@link #reconcile} 承担；
     * 单笔失败仅记日志、不阻断其余。返回实际推进为终态的笔数。
     */
    public int reconcilePending(int limit) {
        int advanced = 0;
        for (PaymentIntent intent : intents.findByStatus(PaymentStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))) {
            if (intent.getGatewayRef() == null) {
                continue;
            }
            try {
                if (reconcile(intent.getPublicToken()).isTerminal()) {
                    advanced++;
                }
            } catch (RuntimeException e) {
                // 单笔失败不阻断批量；仅记类名，不 log token/正文。
                log.warn("对账单笔失败: {}", e.getClass().getSimpleName());
            }
        }
        return advanced;
    }
}

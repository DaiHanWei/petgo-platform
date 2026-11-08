package com.tailtopia.pay.service;

import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 收款对账（轮询通道 · GemPay 对接）。回调缺失/迟到时，主动查网关收款结果并推进意图——喂给
 * {@link PaymentIntentService#applyResult} <b>同一单一收口</b>，故与回调形成双通道且<b>只推进一次</b>
 * （Redis 前置 + {@code gateway_ref} 唯一 + 终态守卫）。
 *
 * <p><b>仅收款，不涉及 payout。</b> 系统/运维级能力（非 App 面向）——App 状态查询仍走
 * {@link PaymentIntentService#statusOf}（只读本地库，不刷网关，延续 Story 1.5 契约）。
 *
 * <p>🔴 v1.4.2 shop Story 3.3（I2 / P-7）：<b>网关调用不在任何数据库事务内</b>。本类方法一律无事务：
 * 读意图走 {@link PaymentIntentService} 的只读短事务 → 事务外 {@link #query} → {@code applyResult} 自身短事务。
 * 原 {@code reconcile} 整体 {@code @Transactional}，会把一次网络往返关在持有意图行的事务里。
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
     * 查单（三态，<b>无事务</b>）：{@code gateway_ref} 为空 → {@code unknown()}（从未出码，网关查不到）；否则调网关。
     */
    public ChargeQueryResult query(PaymentIntent intent) {
        if (intent == null || intent.getGatewayRef() == null) {
            return ChargeQueryResult.unknown();
        }
        ChargeQueryResult r = gateway.queryCharge(intent.getGatewayRef());
        return r == null ? ChargeQueryResult.unknown() : r;
    }

    /**
     * 对账单笔：按 {@code public_token} 定位意图 → 需要查则事务外查网关 → 结果为 PAID，或网关明确 FAILED / EXPIRED
     * 时经单一收口推进（PENDING / UNKNOWN 不推进）→ 重读返回当前状态。无匹配意图 → 404。
     *
     * <p>终态守卫：非电商意图已终态 → 不查（行为不变）；电商意图 PAID → 不查；电商意图 FAILED / EXPIRED →
     * <b>照查</b>（找回关单后到账的迟到款，I1）。
     */
    public PaymentStatus reconcile(String publicToken) {
        PaymentIntent intent = paymentIntentService.findByToken(publicToken)
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        if (shouldQuery(intent)) {
            ChargeQueryResult r = query(intent);
            if (r.outcome() == ChargeQueryResult.Outcome.PAID || r.isTerminalNotPaid()) {
                paymentIntentService.applyResult(r.callback());
            }
        }
        // 重新读取（applyResult 可能已推进）。
        return paymentIntentService.findByToken(publicToken)
                .map(PaymentIntent::getStatus)
                .orElse(intent.getStatus());
    }

    /** 是否值得查网关：无网关单号不查；PAID 不查；非电商终态不查；电商 FAILED / EXPIRED 照查。 */
    static boolean shouldQuery(PaymentIntent intent) {
        if (intent.getGatewayRef() == null || intent.getStatus() == PaymentStatus.PAID) {
            return false;
        }
        if (!intent.getStatus().isTerminal()) {
            return true;
        }
        return intent.getPurpose() == PaymentPurpose.SHOP_ORDER;
    }

    /**
     * 对账一批未终态意图（供运维批量调用；当前无生产调用方）。逐笔：事务外查 → {@code applyResult}；
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
                ChargeQueryResult r = query(intent);
                if (r.outcome() == ChargeQueryResult.Outcome.PAID || r.isTerminalNotPaid()) {
                    paymentIntentService.applyResult(r.callback());
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

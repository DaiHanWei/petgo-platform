package com.tailtopia.admin.paysim.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⚠️ <b>stag 专用</b>（仅 stag 分支，<b>绝不合并回 v1.1-dev / main</b>）：后台手动模拟支付回调工具。
 *
 * <p>stag 跑真 live GemPay，无法真付款触发回调，故运营需手动把支付订单推向 成功/失败/过时。
 * 本 service 走<b>唯一收口</b> {@link PaymentIntentService#applyCallback} 推进意图状态机，
 * 复用其幂等（Redis 去重 + 终态守卫 + 乐观锁）与到账事件下游（发币/解锁/建单/建 IM 会话）。
 *
 * <p>运行时门控在 {@code AdminPaymentController} 的 {@code petgo.pay.simulator-enabled} flag
 * （只在 stag env 开；prod 不开则 POST 端点拒绝）。
 */
@Service
public class AdminPaySimulatorService {

    /** 三态 ↔ 网关归一状态。 */
    public enum Target {
        SUCCESS(GatewayStatus.PAID),
        FAILED(GatewayStatus.FAILED),
        EXPIRED(GatewayStatus.EXPIRED);

        private final GatewayStatus gatewayStatus;

        Target(GatewayStatus gatewayStatus) {
            this.gatewayStatus = gatewayStatus;
        }

        public GatewayStatus gatewayStatus() {
            return gatewayStatus;
        }
    }

    private final PaymentIntentRepository intents;
    private final PaymentIntentService paymentIntentService;
    private final AdminAuditService auditService;

    public AdminPaySimulatorService(PaymentIntentRepository intents,
            PaymentIntentService paymentIntentService, AdminAuditService auditService) {
        this.intents = intents;
        this.paymentIntentService = paymentIntentService;
        this.auditService = auditService;
    }

    /**
     * 模拟一次网关回调，把订单推向目标态。已终态订单由 {@code applyCallback} 幂等吞掉（无副作用）。
     *
     * @return 面向后台的结果提示
     * @throws IllegalArgumentException 订单不存在
     */
    @Transactional
    public String simulate(String publicToken, Target target, Long adminId) {
        PaymentIntent intent = intents.findByPublicToken(publicToken)
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在：" + publicToken));
        GatewayStatus gs = target.gatewayStatus();
        paymentIntentService.applyCallback(new PaymentCallback(
                publicToken, "SIM-" + target + "-" + publicToken, gs,
                Map.of("simulated", true, "source", "admin-pay-simulator", "actor", adminId)));
        auditService.record(adminId, "PAYMENT_CALLBACK_SIMULATED", "PAYMENT_INTENT",
                publicToken, "模拟支付回调 目标=" + gs + " 用途=" + intent.getPurpose());
        return "已模拟回调：" + publicToken + " → " + gs + "（若已是终态则无变化）";
    }
}

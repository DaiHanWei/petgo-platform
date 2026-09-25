package com.tailtopia.admin.payment.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * staging 支付模拟：模拟回调 + 审计<b>同一事务</b>（2026-09-25 code review #12）。
 *
 * <p>原先两步在控制器里各自提交：审计写失败时钱已标成已付、入账也跑了，却没有审计记录 ——
 * 恰好是「这笔是模拟出来的」唯一可追溯的证据丢了。{@code applyCallback} 与
 * {@code AdminAuditService#record} 都是 REQUIRED，包进这里即同成同败。
 */
@StagOnly
@Service
public class AdminPaymentSimulateService {

    private final PaymentIntentService intents;
    private final AdminAuditService audit;

    public AdminPaymentSimulateService(PaymentIntentService intents, AdminAuditService audit) {
        this.intents = intents;
        this.audit = audit;
    }

    @Transactional
    public void simulate(long adminAccountId, String intentToken, GatewayStatus result) {
        // ⚠️ 可分辨性靠 rawMeta 的 simulated 标记 + 审计行，不是 gatewayRef 前缀（见控制器注释）。
        intents.applyCallback(new PaymentCallback(intentToken, "sim-" + intentToken, result,
                Map.of("simulated", true)));
        audit.record(adminAccountId, "PAYMENT_SIMULATE_CALLBACK", "payment_intent",
                intentToken, "result=" + result.name());
    }
}

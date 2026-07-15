package com.tailtopia.triage.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.domain.UnlockChannel;
import com.tailtopia.triage.domain.UnlockMethod;
import com.tailtopia.triage.domain.UnlockSource;
import com.tailtopia.triage.dto.TriageResultResponse;
import com.tailtopia.triage.dto.UnlockResponse;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 详建付费/额度解锁编排（Story 2.3，Epic 2 集成枢纽）。串 2-1 免费额度、2-2 解锁跃迁、1.x 支付/PawCoin。
 *
 * <p><b>三路径</b>（{@link #unlock}）：
 * <ul>
 *   <li>{@code FREE_QUOTA}：{@link FreeQuotaService#tryConsume} → {@link TriageTask#unlock}(FREE_QUOTA)，同事务；额度不足抛 409。</li>
 *   <li>{@code PAWCOIN}：{@link PawCoinWalletService#debit} → unlock(PAID,PAWCOIN) → 建 COMPLETED 订单，同事务；余额不足回滚。</li>
 *   <li>{@code QRIS}：{@link PaymentIntentService#createIntent} + PENDING_PAYMENT 订单，返回支付信息；到账由
 *       {@link #completeCashUnlock} 解锁（{@code AiUnlockPaidHandler} 在回调同事务内调）。</li>
 * </ul>
 *
 * <p>🔒 <b>入口双短路（在任何扣费分支之前）</b>：① {@code dangerLevel==RED} → 不扣费直接放行（AC6，红色永不锁，
 * 与 2-2 展示侧单点呼应，收费侧双保险）；② 已解锁（{@code unlock_source∈{FREE_QUOTA,PAID}}）→ 不重复扣费直接回结果（AC5）。
 *
 * <p><b>多层幂等不重复扣费</b>：入口 alreadyUnlocked 短路 + {@link TriageTask#unlock} 不可覆盖 + PawCoin debit 幂等键
 * {@code ai-unlock:{triageId}} + createIntent 幂等键 + {@code ai_consult_orders} 唯一约束 + 回调双通道去重。
 */
@Service
public class AiUnlockService {

    private static final Logger log = LoggerFactory.getLogger(AiUnlockService.class);
    private static final String REF_TYPE = "AI_UNLOCK";
    private static final String CURRENCY = "IDR";

    private final TriageTaskRepository tasks;
    private final FreeQuotaService freeQuota;
    private final PawCoinWalletService wallet;
    private final PaymentIntentService paymentIntents;
    private final AiConsultOrderRepository orders;
    private final CardTokenGenerator tokenGenerator;
    private final com.tailtopia.config.service.PlatformConfigService platformConfig;

    public AiUnlockService(TriageTaskRepository tasks, FreeQuotaService freeQuota,
            PawCoinWalletService wallet, PaymentIntentService paymentIntents,
            AiConsultOrderRepository orders, CardTokenGenerator tokenGenerator,
            com.tailtopia.config.service.PlatformConfigService platformConfig) {
        this.tasks = tasks;
        this.freeQuota = freeQuota;
        this.wallet = wallet;
        this.paymentIntents = paymentIntents;
        this.orders = orders;
        this.tokenGenerator = tokenGenerator;
        this.platformConfig = platformConfig;
    }

    /**
     * 解锁 AI 详建。同步路径（FREE_QUOTA/PAWCOIN）在本 {@code @Transactional} 内原子完成；现金路径建意图+订单后返回支付信息。
     * 仅本人（非 owner/不存在统一 403 防枚举）+ 仅 DONE（否则 409）。
     */
    @Transactional
    public UnlockResponse unlock(long userId, long triageId, UnlockMethod method) {
        TriageTask task = tasks.findById(triageId).orElse(null);
        if (task == null || task.getUserId() != userId) {
            throw AppException.forbidden("无权访问该分诊任务");
        }
        if (task.getStatus() != TriageStatus.DONE) {
            throw AppException.conflict("分诊尚未完成，暂不能解锁");
        }
        // 🔒 入口短路（在任何扣费之前）：红色永不锁不扣费（AC6）+ 已解锁不重复扣费（AC5）。
        if (task.getDangerLevel() == DangerLevel.RED || isUnlocked(task)) {
            return UnlockResponse.unlocked(TriageResultResponse.from(task));
        }

        long price = platformConfig.pricing().getAiUnlockPrice();
        return switch (method) {
            case FREE_QUOTA -> unlockByFreeQuota(userId, task);
            case PAWCOIN -> unlockByPawCoin(userId, task, price);
            case QRIS -> createCashUnlock(userId, task, price, PayChannel.QRIS);
        };
    }

    private UnlockResponse unlockByFreeQuota(long userId, TriageTask task) {
        if (!freeQuota.tryConsume(userId)) {
            throw AppException.conflict("本月免费额度已用完，请选择付费解锁");
        }
        task.unlock(UnlockSource.FREE_QUOTA, null);
        tasks.save(task);
        return UnlockResponse.unlocked(TriageResultResponse.from(task));
    }

    private UnlockResponse unlockByPawCoin(long userId, TriageTask task, long price) {
        // 余额不足 → debit 抛冲突 → 整事务回滚（unlock/建单不发生）。幂等键稳定可重放。
        wallet.debit(userId, price, PawCoinTxnType.SPEND, REF_TYPE, task.getId(),
                "ai-unlock:" + task.getId());
        task.unlock(UnlockSource.PAID, UnlockChannel.PAWCOIN);
        tasks.save(task);
        orders.save(AiConsultOrder.completedPawCoin(
                tokenGenerator.generate(), userId, task.getId(), price));
        return UnlockResponse.unlocked(TriageResultResponse.from(task));
    }

    private UnlockResponse createCashUnlock(long userId, TriageTask task, long price, PayChannel channel) {
        // 幂等键 ai-unlock:{triageId}：重复发起取回既有 intent（不双建单）。
        PaymentIntentResponse intent = paymentIntents.createIntent(
                userId, PaymentPurpose.AI_UNLOCK, channel, price, CURRENCY, "ai-unlock:" + task.getId());
        // 订单幂等：同 intent token 已建则复用（唯一约束兜底）。
        orders.findByPaymentIntentToken(intent.token()).orElseGet(() -> orders.save(
                AiConsultOrder.pendingCash(tokenGenerator.generate(), userId, task.getId(), price,
                        channel, intent.token())));
        return UnlockResponse.paymentRequired(intent);
    }

    /**
     * 现金到账后完成解锁（Story 2.3 AC4）。供 {@code AiUnlockPaidHandler} 在 {@code PaymentIntentService.applyCallback}
     * 的<b>同一事务</b>内调（{@code MANDATORY} 强制加入，禁 AFTER_COMMIT 异步）。幂等：订单已 COMPLETED 短路；
     * triage 缺失/已被其它路径解锁做异常/幂等处理，绝不 crash、绝不重复解锁。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeCashUnlock(String intentToken) {
        AiConsultOrder order = orders.findByPaymentIntentToken(intentToken).orElse(null);
        if (order == null) {
            log.warn("AI 解锁到账无匹配订单，忽略 intent={}", intentToken); // 到账但无单（不 crash）
            return;
        }
        if (order.getStatus() == AiConsultOrderStatus.COMPLETED) {
            return; // 幂等：回调/轮询重放
        }
        TriageTask task = tasks.findById(order.getTriageTaskId()).orElse(null);
        if (task == null) {
            order.markAbnormal(); // triage 缺失（如已注销级联删）→ 对账异常
            return;
        }
        // 已被其它路径解锁 或 红色 → 不重复 unlock（幂等），订单标记完成。
        if (isUnlocked(task) || task.getDangerLevel() == DangerLevel.RED) {
            order.markCompleted(Instant.now());
            return;
        }
        task.unlock(UnlockSource.PAID, UnlockChannel.valueOf(order.getPayChannel().name()));
        order.markCompleted(Instant.now());
        log.info("AI 解锁现金到账完成 order={} triage={}", order.getOrderToken(), task.getId());
    }

    private static boolean isUnlocked(TriageTask task) {
        UnlockSource s = task.getUnlockSource();
        return s == UnlockSource.FREE_QUOTA || s == UnlockSource.PAID;
    }
}

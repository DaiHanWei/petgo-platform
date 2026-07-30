package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultStageEvent;
import com.tailtopia.consult.domain.InterruptReason;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.event.ConsultInterruptedEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 封禁挂起强制结束 + 自动退款（Story 3.8，H-5，安全攸关）。
 *
 * <p>付费会话被封禁挂起（{@code suspend_deadline_at} 非空、仍 IN_PROGRESS）→ 到期（{@link #scanExpiredSuspensions}
 * {@code @Scheduled}）或用户主动逃生（{@link #escapeByUser}）→ {@link #forceEndSuspended}：
 * <ol>
 *   <li><b>退款幂等唯一闸</b>：订单 CAS {@code IN_PROGRESS→REFUNDING}（{@code markRefunding} 返回 1 才退款；
 *       scanner 与逃生并发只一方胜，<b>绝不双退</b>）。</li>
 *   <li>按支付方式退款（架构 line 208）：PawCoin → {@link PawCoinWalletService#credit} 立即全额退回 + 订单 REFUNDED
 *       + {@code REFUND_COMPLETED} 节点；QRIS → 订单留 REFUNDING + {@code REFUND_REQUESTED} 节点，
 *       <b>实际 Midtrans Iris 打款留 Epic 4</b>（本 story 不记 ledger，避免与 Epic 4 实际打款双记，D-4 落地决策）。</li>
 *   <li>会话 {@code INTERRUPTED}（VET_BANNED 终态）+ 发 {@link ConsultInterruptedEvent}（推送/历史）。</li>
 * </ol>
 * 全额退、无溢价无 bonus（bonus 只挂「系统故障未交付+转 PawCoin」单一分支 C-1，封禁挂起不属之）。
 */
@Service
public class ConsultSuspensionService {

    private final ConsultSessionRepository sessions;
    private final ConsultOrderRepository orders;
    private final PawCoinWalletService wallet;
    private final ConsultBillingService billing;
    private final ApplicationEventPublisher events;

    public ConsultSuspensionService(ConsultSessionRepository sessions, ConsultOrderRepository orders,
            PawCoinWalletService wallet, ConsultBillingService billing,
            ApplicationEventPublisher events) {
        this.sessions = sessions;
        this.orders = orders;
        this.wallet = wallet;
        this.billing = billing;
        this.events = events;
    }

    /** 15min 挂起超时扫描（@Scheduled 调）：逐条强制结束+退款。返回处理数。幂等可重扫。 */
    @Transactional
    public int scanExpiredSuspensions() {
        List<ConsultSession> expired = sessions.findByStatusAndSuspendDeadlineAtBefore(
                SessionStatus.IN_PROGRESS, Instant.now());
        int handled = 0;
        for (ConsultSession s : expired) {
            forceEndSuspended(s);
            handled++;
        }
        return handled;
    }

    /**
     * 用户主动逃生（Story 3.8，AC5）：立即强制结束挂起会话+退款（不等 15min）。仅本人 + 挂起态可逃生，
     * 否则 404（防枚举）。
     */
    @Transactional
    public void escapeByUser(long userId, long sessionId) {
        ConsultSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("会话不存在或已结束"));
        if (s.getUserId() == null || s.getUserId() != userId) {
            throw AppException.notFound("会话不存在或已结束"); // 归属不符按不存在（防枚举）
        }
        if (s.getStatus() != SessionStatus.IN_PROGRESS || s.getSuspendDeadlineAt() == null) {
            throw AppException.notFound("会话未处于挂起态"); // 非挂起不可逃生
        }
        forceEndSuspended(s);
    }

    /**
     * 强制结束挂起会话 + 按支付方式退款（同事务原子）。幂等：仅 IN_PROGRESS 处理；退款走订单 CAS 单点。
     */
    @Transactional
    public void forceEndSuspended(ConsultSession s) {
        if (s.getStatus() != SessionStatus.IN_PROGRESS) {
            return; // 已被处理（幂等）
        }
        Long vetId = s.getVetId();
        // bug 20260721-324：按本会话 consult_session_id 精确取待退款订单，不用 (user,vet) 松匹配。
        ConsultOrder order = vetId == null ? null
                : orders.findByConsultSessionIdAndStatus(
                        s.getId(), ConsultOrderStatus.IN_PROGRESS).orElse(null);
        if (order != null && orders.markRefunding(order.getId()) == 1) {
            // 本路拿到退款闸（另一路并发拿 0 → 不重复退）。
            refundByChannel(order);
        }
        s.interrupt(InterruptReason.VET_BANNED); // → INTERRUPTED 终态（不评分不存档），清挂起锚
        sessions.save(s);
        events.publishEvent(new ConsultInterruptedEvent(
                s.getId(), s.getUserId(), vetId == null ? 0L : vetId,
                InterruptReason.VET_BANNED.name()));
    }

    /** 按支付方式退款：PawCoin 立即全额退回 + REFUNDED；QRIS 留 REFUNDING（Epic 4 打款）。 */
    private void refundByChannel(ConsultOrder order) {
        Instant now = Instant.now();
        String idem = "consult-refund-" + order.getId();
        if (order.getPayChannel() == PayChannel.PAWCOIN) {
            // 退回 PawCoin（credit 内部记 FLOAT_LIABILITY 分录 + 幂等）；全额=用户实付 amount。
            wallet.credit(order.getUserId(), order.getAmount(), PawCoinTxnType.REFUND,
                    "CONSULT_ORDER", order.getId(), idem);
            orders.markRefunded(order.getId()); // REFUNDING→REFUNDED
            billing.appendStageEvent(order.getId(), ConsultStageEvent.REFUND_COMPLETED, now,
                    "封禁挂起自动退款（PawCoin 全额退回）");
        } else {
            // QRIS/现金：订单留 REFUNDING，实际 Midtrans Iris 打款由 Epic 4 完成（本 story 不记 ledger 避免双记）。
            billing.appendStageEvent(order.getId(), ConsultStageEvent.REFUND_REQUESTED, now,
                    "封禁挂起退款待打款（QRIS，Epic 4 执行）");
        }
    }
}

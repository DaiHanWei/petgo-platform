package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStageEvent;
import com.tailtopia.consult.domain.ConsultStageEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultOrderStageEventRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.service.CardTokenGenerator;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医咨询计费建单原语（Story 3.1）。支付成功（3-4）时原子建 {@code consult_orders}(IN_PROGRESS) + 首条
 * {@code PAID} 节点事件；后续节点（会话起止/退款）经 {@link #appendStageEvent} 只追加。
 *
 * <p><b>本 story 只建原语，不接支付/会话链路</b>（3-4 调）。{@code order_token} 用 {@link CardTokenGenerator}
 * 不可枚举。stage events <b>append-only</b>：只 save 新行，绝不改历史。
 */
@Service
public class ConsultBillingService {

    private final ConsultOrderRepository orders;
    private final ConsultOrderStageEventRepository stageEvents;
    private final CardTokenGenerator tokenGenerator;

    public ConsultBillingService(ConsultOrderRepository orders,
            ConsultOrderStageEventRepository stageEvents, CardTokenGenerator tokenGenerator) {
        this.orders = orders;
        this.stageEvents = stageEvents;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * 支付成功建单：IN_PROGRESS 订单（成交快照 + paid_at）+ 首条 {@code PAID} 节点。返回建好的订单。
     * 金额/分成/单价快照由调用方（3-4）从 config 取（后台 9-2 改价不影响历史）。
     */
    @Transactional
    public ConsultOrder createOrder(long userId, long vetId, long petProfileId, long amount,
            PayChannel payChannel, Long paymentIntentId, long vetPayout, int vetShareRate,
            long unitPrice, Instant paidAt) {
        ConsultOrder order = orders.save(ConsultOrder.inProgress(tokenGenerator.generate(), userId,
                vetId, petProfileId, amount, payChannel, paymentIntentId, vetPayout, vetShareRate,
                unitPrice, paidAt));
        appendStageEvent(order.getId(), ConsultStageEvent.PAID, paidAt, null);
        return order;
    }

    /** 追加一条订单节点事件（append-only，绝不覆盖历史）。 */
    @Transactional
    public ConsultOrderStageEvent appendStageEvent(long consultOrderId, ConsultStageEvent type,
            Instant occurredAt, String note) {
        return stageEvents.save(ConsultOrderStageEvent.of(consultOrderId, type, occurredAt, note));
    }

    /**
     * 支付成功建 IM 会话后记会话起始（Story 3.4）：置订单 {@code session_started_at} + 追加 {@code SESSION_STARTED}
     * 节点。{@code note} 可存 IM 会话标识（对账用；会话实体在 {@code consult_sessions}，本表记时间戳）。
     */
    @Transactional
    public void markSessionStarted(ConsultOrder order, Instant startedAt, String note, long consultSessionId) {
        order.markSessionStarted(startedAt, consultSessionId);
        orders.save(order);
        appendStageEvent(order.getId(), ConsultStageEvent.SESSION_STARTED, startedAt, note);
    }
}

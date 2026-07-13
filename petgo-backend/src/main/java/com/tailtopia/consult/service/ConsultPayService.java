package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.dto.ConsultPayResponse;
import com.tailtopia.consult.event.ConsultRequestFailedEvent;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.consult.ConsultProperties;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.ImAccountMapper;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限时支付、建会话与转单编排（Story 3.4，计费流闭环）。承接 3-3 接单（{@code ACCEPTED_AWAIT_PAY}）：
 * 支付成功 → 建 {@code consult_orders}(IN_PROGRESS) + 建腾讯 IM {@code consult_sessions}（复用 Epic 5 会话机器：
 * 付费问诊可结束/评分/进历史）+ CAS 删 request（A-5 两表拆分的「转单」）。
 *
 * <p><b>三渠道</b>（照 2-3 {@code AiUnlockService} 范式）：
 * <ul>
 *   <li>{@code PAWCOIN}：站内即时扣（{@link #payByPawCoin}），同 {@code @Transactional} 内建单+建会话+转单原子完成。</li>
 *   <li>{@code QRIS}：现金异步（{@link #createCashPay}），{@link PaymentIntentService#createIntent}
 *       返回支付信息、<b>此刻不建单</b>；到账由 {@code ConsultPaidHandler} 在回调同事务内调
 *       {@link #completePaidConsult} 建单建会话转单。</li>
 * </ul>
 *
 * <p>🔒 <b>已扣未交付（AC5）</b>：IM 建会话在支付之后——PawCoin 路径 IM 失败即<b>整事务回滚</b>（扣币撤销、用户未扣费、
 * 请求仍待支付，返回 503 可重试）；现金路径 Midtrans 已捕获不可回滚，IM 失败/无在途请求 = 真「已扣未交付」→ 触发
 * <b>系统故障落地</b>（清 request 防再超时 + 释放兽医 + {@code failed_consult_requests}(SYSTEM_FAILURE)，运营跟进）。
 * <b>实际退款执行（转 PawCoin 附 bonus / 真钱）+ 用户通知留 Epic 4</b>（本 story 只触发路径、不建退款引擎，[已定-1]）。
 */
@Service
public class ConsultPayService {

    private static final Logger log = LoggerFactory.getLogger(ConsultPayService.class);
    private static final String REF_TYPE = "VET_CONSULT";
    private static final String CURRENCY = "IDR";

    private final ConsultRequestRepository requests;
    private final ConsultBillingService billing;
    private final ConsultSessionRepository sessions;
    private final TencentImClient imClient;
    private final PaymentIntentService paymentIntents;
    private final PawCoinWalletService wallet;
    private final VetPresenceService presence;
    private final ConsultProperties props;
    private final ApplicationEventPublisher events;

    public ConsultPayService(ConsultRequestRepository requests, ConsultBillingService billing,
            ConsultSessionRepository sessions, TencentImClient imClient,
            PaymentIntentService paymentIntents, PawCoinWalletService wallet,
            VetPresenceService presence, ConsultProperties props, ApplicationEventPublisher events) {
        this.requests = requests;
        this.billing = billing;
        this.sessions = sessions;
        this.imClient = imClient;
        this.paymentIntents = paymentIntents;
        this.wallet = wallet;
        this.presence = presence;
        this.props = props;
        this.events = events;
    }

    /**
     * 限时支付入口（AC2）。守卫：本人 + {@code ACCEPTED_AWAIT_PAY} + 未暂停 + 支付窗未过期（服务端权威）。
     * 归属/状态不符统一 409 防枚举。按渠道分派同步（PawCoin）/ 异步（现金）。
     */
    @Transactional
    public ConsultPayResponse pay(long userId, String requestToken, PayChannel channel) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.conflict("该请求不存在或已处理"));
        if (req.getUserId() == null || req.getUserId() != userId
                || req.getState() != ConsultRequestState.ACCEPTED_AWAIT_PAY) {
            throw AppException.conflict("该请求不存在或已处理"); // 防枚举（与不存在同码）
        }
        if (req.getPausedAt() != null) {
            throw AppException.conflict("支付计时已暂停，请先返回继续");
        }
        if (req.getPayDeadlineAt() == null || req.getPayDeadlineAt().isBefore(Instant.now())) {
            throw AppException.conflict("支付窗已过期"); // 交扫描器回队重播
        }
        long price = props.getUnitPrice();
        return switch (channel) {
            case PAWCOIN -> payByPawCoin(req, price);
            case QRIS -> createCashPay(req, price, PayChannel.QRIS);
        };
    }

    /** PawCoin 站内即时支付（AC3，同事务原子）：扣币 → 建 IM 会话 → 建单转单。IM 失败整事务回滚（未扣费）。 */
    private ConsultPayResponse payByPawCoin(ConsultRequest req, long price) {
        long userId = req.getUserId();
        // 幂等键稳定 = requestToken；余额不足 debit 抛 409 → 整事务回滚（不建单不建会话）。
        wallet.debit(userId, price, PawCoinTxnType.SPEND, REF_TYPE, req.getId(),
                "vet-consult:" + req.getRequestToken());
        String conv;
        try {
            conv = createImConversation(userId, req.getVetId()); // 纯 IM，无 DB
        } catch (RuntimeException e) {
            // IM 建会话失败 → 503 → 整事务回滚（扣币撤销，用户未扣费），可安全重试。
            log.warn("PawCoin 支付建 IM 会话失败，回滚不扣费 user={} cause={}",
                    userId, e.getClass().getSimpleName());
            throw AppException.serviceUnavailable("系统繁忙，请稍后重试");
        }
        ConsultOrder order = buildOrderAndConvert(req, PayChannel.PAWCOIN, null, conv);
        return ConsultPayResponse.done(order);
    }

    /** 现金异步发起（AC4）：建 VET_CONSULT 支付意图返回支付信息，<b>不建单</b>；到账由 handler 转单。 */
    private ConsultPayResponse createCashPay(ConsultRequest req, long price, PayChannel channel) {
        PaymentIntentResponse intent = paymentIntents.createIntent(req.getUserId(),
                PaymentPurpose.VET_CONSULT, channel, price, CURRENCY,
                "vet-consult:" + req.getRequestToken());
        return ConsultPayResponse.paymentRequired(intent);
    }

    /**
     * 现金到账后转单（AC4，供 {@code ConsultPaidHandler} 在 {@code applyCallback} 同事务内调，{@code MANDATORY}）。
     * 按 userId 锚定该用户唯一 {@code ACCEPTED_AWAIT_PAY} request（占用不变量，[已定-3]）转单建会话；
     * 无在途请求 / IM 失败 / 并发认领失败 = 已扣未交付 → 系统故障落地（不 rethrow，markPaid 须提交）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completePaidConsult(long userId, PayChannel channel, Long paymentIntentId) {
        ConsultRequest req = requests.findFirstByUserIdAndState(
                userId, ConsultRequestState.ACCEPTED_AWAIT_PAY).orElse(null);
        if (req == null) {
            recordCashSystemFault(userId, null); // 无在途请求（已超时回退）：已扣未交付
            return;
        }
        String conv;
        try {
            conv = createImConversation(userId, req.getVetId()); // 纯 IM，无 DB（失败前不碰库）
        } catch (RuntimeException e) {
            recordCashSystemFault(userId, req); // 现金 IM 失败 = 已扣未交付，不 rethrow
            return;
        }
        try {
            buildOrderAndConvert(req, channel, paymentIntentId, conv);
        } catch (AppException e) {
            // 并发认领失败（请求已被回退/他路处理）= 已扣未交付。claim-first 保证此时未建单/会话。
            recordCashSystemFault(userId, req);
        }
    }

    /**
     * 转单原语：CAS 认领 request（先删，保 H-4 CAS 完整性）→ 建 {@code consult_sessions}(IN_PROGRESS+IM) →
     * 建 {@code consult_orders}(IN_PROGRESS 成交快照) → 记会话起始。认领失败（0 行）抛 409（未建任何行）。
     * conv 已由调用方先建（纯 IM，失败已在外层处理）。
     */
    private ConsultOrder buildOrderAndConvert(ConsultRequest req, PayChannel channel,
            Long paymentIntentId, String conv) {
        long userId = req.getUserId();
        long vetId = req.getVetId();
        // CAS 认领（先删）：0 行=并发已回退/他路处理 → 抛回滚，此前未建任何 session/order（幂等安全）。
        if (requests.deleteIfState(req.getId(), ConsultRequestState.ACCEPTED_AWAIT_PAY) != 1) {
            throw AppException.conflict("该请求已被处理");
        }
        Instant now = Instant.now();
        // 建 consult_sessions（复用 Epic 5 会话机器：付费问诊可结束/评分/进历史，[已定-6]）。
        ConsultSession session = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        session.markInProgress(vetId);
        session.attachImConversation(conv);
        sessions.save(session);
        // 建订单（成交快照）+ 记会话起始节点。金额/分成/单价成交时落快照（后台改价不影响历史）。
        ConsultOrder order = billing.createOrder(userId, vetId, req.getPetProfileId(),
                props.getUnitPrice(), channel, paymentIntentId, props.vetPayout(),
                props.getVetShareRate(), props.getUnitPrice(), now);
        billing.markSessionStarted(order, now, "im:" + conv);
        return order;
    }

    /** 幂等确保 IM 账号 + 建 C2C 会话，返回 conversationId（纯 IM，无 DB 副作用）。 */
    private String createImConversation(long userId, long vetId) {
        String userImId = ImAccountMapper.userImId(userId);
        imClient.ensureAccount(userImId, "用户" + userId);
        String vetImId = ImAccountMapper.vetImId(vetId);
        imClient.ensureAccount(vetImId, "兽医" + vetId);
        return imClient.createConversation(userImId, vetImId);
    }

    /**
     * 现金「已扣未交付」系统故障落地（AC5）：清 request 防再超时 + 释放兽医 + 落 {@code failed_consult_requests}
     * (SYSTEM_FAILURE)，运营跟进。<b>不 rethrow</b>（markPaid 须随本事务提交，钱已入账）；
     * <b>实际退款（转 PawCoin 附 bonus / 真钱）+ 用户通知留 Epic 4</b>（避免 notifications.type CHECK 迁移；[已定-1]）。
     */
    private void recordCashSystemFault(long userId, ConsultRequest req) {
        int onlineVets = presence.onlineVetIds().size();
        if (req != null) {
            if (requests.deleteIfState(req.getId(), ConsultRequestState.ACCEPTED_AWAIT_PAY) == 1
                    && req.getVetId() != null) {
                presence.goAvailable(req.getVetId()); // 释放兽医（handler 无外层请求事务，直接置）
            }
            events.publishEvent(new ConsultRequestFailedEvent(
                    "SYSTEM_FAILURE", userId, 0L, req.getCreatedAt(), onlineVets));
        } else {
            events.publishEvent(new ConsultRequestFailedEvent(
                    "SYSTEM_FAILURE", userId, 0L, Instant.now(), onlineVets));
        }
        log.warn("现金支付已捕获但未交付（IM 失败/无在途请求），落系统故障待退款 user={} hadRequest={}",
                userId, req != null);
    }
}

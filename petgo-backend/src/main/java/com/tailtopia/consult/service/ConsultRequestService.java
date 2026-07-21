package com.tailtopia.consult.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.dto.ConsultAiContextResponse;
import com.tailtopia.consult.dto.CreateConsultationRequest;
import com.tailtopia.consult.dto.VetQueueResponse;
import com.tailtopia.consult.event.ConsultRequestFailedEvent;
import com.tailtopia.consult.event.ConsultRequestQueuedForBillingEvent;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.consult.ConsultProperties;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.dto.TriageUpgradeContext;
import com.tailtopia.triage.service.TriageService;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 免费入队业务（Story 3.2，Epic 3 计费流入口）。用户免费发起咨询 → 建 {@code consult_requests}(QUEUEING)
 * <b>不扣费、不建订单</b>（A-5 红线）+ 发广播事件（notify 侧推在线兽医，FR-22E）。入队超时由
 * {@code ConsultRequestTimeoutScanner} 物理删（无痕）。
 *
 * <p><b>不碰 V1.0 免费直连流</b>（{@code ConsultSessionService}/{@code consult_sessions}）——本 service 只管
 * 计费流的 {@code consult_requests}。接单 CAS 在 3-3、限时支付建单在 3-4。
 */
@Service
public class ConsultRequestService {

    /** 入队无接单超时（秒，服务端权威计时）。 */
    public static final long QUEUE_TIMEOUT_SECONDS = 60;

    /** 接单后限时支付窗（秒，5min，服务端权威计时，FR-53A）。不信客户端计时。 */
    public static final long PAY_WINDOW_SECONDS = 300;

    private final ConsultRequestRepository requests;
    private final PetProfileRepository petProfiles;
    private final CardTokenGenerator tokenGenerator;
    private final ApplicationEventPublisher events;
    private final VetPresenceService presence;
    private final ConsultProperties props;
    private final PetProfileQueryService petQuery;
    private final AccountQueryService accounts;
    private final TriageService triageService;
    private final SignedUrlService signedUrlService;
    private final PaymentIntentService paymentIntents;

    public ConsultRequestService(ConsultRequestRepository requests, PetProfileRepository petProfiles,
            CardTokenGenerator tokenGenerator, ApplicationEventPublisher events,
            VetPresenceService presence, ConsultProperties props,
            PetProfileQueryService petQuery, AccountQueryService accounts,
            TriageService triageService, SignedUrlService signedUrlService,
            PaymentIntentService paymentIntents) {
        this.triageService = triageService;
        this.signedUrlService = signedUrlService;
        this.requests = requests;
        this.petProfiles = petProfiles;
        this.tokenGenerator = tokenGenerator;
        this.events = events;
        this.presence = presence;
        this.props = props;
        this.petQuery = petQuery;
        this.accounts = accounts;
        this.paymentIntents = paymentIntents;
    }

    /** 发起结果：新建请求 or 命中已有 live 请求（alreadyActive=true，前端跳「进行中」）。 */
    public record CreateResult(ConsultRequest request, boolean alreadyActive) {
    }

    /**
     * 接单结果（Story 3.3）：由 CAS 成功后的权威值构造，<b>不 reload 实体</b>（{@code tryAccept} 是绕过持久化
     * 上下文的 bulk update，reload 会读到 stale 缓存）。state 恒为 {@code ACCEPTED_AWAIT_PAY}，payDeadline
     * 即本次写入 DB 的服务端权威时刻。
     */
    public record AcceptResult(String requestToken, ConsultRequestState state, Instant payDeadlineAt) {
    }

    /**
     * 免费发起咨询入队（无病例，兼容既有调用方/测试）。
     *
     * @deprecated 病例是兽医接单判断依据（D1），新调用方一律走
     *     {@link #createRequest(long, CreateConsultationRequest)}。
     */
    @Deprecated
    @Transactional
    public CreateResult createRequest(long userId) {
        return createRequest(userId, new CreateConsultationRequest(null, null, null, null));
    }

    /**
     * 免费发起咨询入队（Story 3.2 + [OPEN] 收口 D1/D2）。占用命中（FR-4B 同时仅 1 个）返回现有；
     * 否则建 QUEUEING（token/deadline=+1min）+ <b>落病例</b> + 发广播。<b>绝不扣费、绝不建 consult_orders</b>。
     *
     * <p>病例两来源（D2，与 {@link ConsultSessionService#createWaiting}/{@code createWaitingFromUpgrade} 对齐）：
     * <ul>
     *   <li>{@code DIRECT}：用户自填症状 + 私密图 key（前端已直传，后端不收签名 URL）；</li>
     *   <li>{@code AI_UPGRADE}：经 {@link TriageService} 拉评级/症状/图定格快照（禁直读 triage repo），
     *       <b>红线兜底：RED 一律拒绝入队</b>（即便前端误放入口）。</li>
     * </ul>
     */
    @Transactional
    public CreateResult createRequest(long userId, CreateConsultationRequest body) {
        // 占用校验：consult_requests 内存在即 live（取消/超时已删）。
        if (requests.existsByUserId(userId)) {
            return new CreateResult(requests.findFirstByUserIdOrderByCreatedAtAsc(userId).orElseThrow(),
                    true);
        }
        // 归属：一人一宠（findByOwnerId），无档案不能发起。
        PetProfile pet = petProfiles.findByOwnerId(userId)
                .orElseThrow(() -> AppException.conflict("需先建立宠物档案后再发起问诊"));
        Instant queueDeadline = Instant.now().plus(Duration.ofSeconds(QUEUE_TIMEOUT_SECONDS));
        ConsultRequest req = ConsultRequest.queue(
                userId, pet.getId(), tokenGenerator.generate(), queueDeadline);
        bindCase(userId, req, body);
        ConsultRequest saved = requests.save(req);
        // AFTER_COMMIT 广播在线兽医（notify 侧监听）：请求已落库才推，不吞资金写（此处非资金）。
        events.publishEvent(new ConsultRequestQueuedForBillingEvent(saved.getId()));
        return new CreateResult(saved, false);
    }

    /** 绑病例：AI_UPGRADE 拉 triage 快照（RED 兜底拒绝）；否则绑自填病例（都为空则不绑）。 */
    private void bindCase(long userId, ConsultRequest req, CreateConsultationRequest body) {
        if (body != null && body.isAiUpgrade()) {
            if (body.triageTaskId() == null) {
                throw AppException.validation("升级发起需带分诊任务");
            }
            TriageUpgradeContext ctx = triageService.getResultForUpgrade(userId, body.triageTaskId());
            if (ctx.dangerLevel() == DangerLevel.RED) {
                // 架构不可协商：红色态零兽医引流。
                throw AppException.forbidden("高危情况请立即就医，本通道不提供升级");
            }
            req.bindAiContext(ctx.triageTaskId(), ctx.dangerLevel().name(),
                    ctx.symptomText(), ctx.imageObjectKeys());
            return;
        }
        if (body == null) {
            return;
        }
        boolean hasText = body.symptomText() != null && !body.symptomText().isBlank();
        boolean hasImages = body.imageObjectKeys() != null && !body.imageObjectKeys().isEmpty();
        if (hasText || hasImages) {
            req.bindDirectCase(body.symptomText(), body.imageObjectKeys());
        }
    }

    /** 入队超时静默物理删（@Scheduled 调）。返回删除行数。不建订单、不留痕（A-5）。 */
    @Transactional
    public int purgeExpiredQueue() {
        return requests.deleteExpiredQueueing(Instant.now());
    }

    /**
     * 请求状态查询（Story 3.5，只读，前端下单三屏轮询）。仅本人；不存在（含已超时删/已转单删）或非本人 →
     * 404（防枚举）。返回 state + 服务端权威 deadline + pausedAt，供前端驱动跃迁与倒计时。
     */
    @Transactional(readOnly = true)
    public ConsultRequest statusOf(long userId, String requestToken) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.notFound("该请求不存在或已结束"));
        if (req.getUserId() == null || req.getUserId() != userId) {
            throw AppException.notFound("该请求不存在或已结束"); // 归属不符按不存在处理（防枚举）
        }
        return req;
    }

    /**
     * 延长排队（bug 20260720-311）：排队超时前用户点「继续排队」→ 仅本人 + {@code QUEUEING} + 未暂停 →
     * CAS 顺延 {@code queue_deadline_at = now + QUEUE_TIMEOUT_SECONDS}。归属/状态不符（含已被 purge 删/已接单）
     * → 404（防枚举，前端据此退出）。返回新 deadline。
     */
    @Transactional
    public Instant extendQueue(long userId, String requestToken) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.notFound("该请求不存在或已结束"));
        if (req.getUserId() == null || req.getUserId() != userId
                || req.getState() != ConsultRequestState.QUEUEING) {
            throw AppException.notFound("该请求不存在或已结束");
        }
        Instant newQueueDeadline = Instant.now().plus(Duration.ofSeconds(QUEUE_TIMEOUT_SECONDS));
        if (requests.extendQueueDeadlineIfQueueing(req.getId(), newQueueDeadline) != 1) {
            throw AppException.notFound("该请求不存在或已结束"); // 竞态：刚被 purge/接单
        }
        return newQueueDeadline;
    }

    /**
     * 兽医计费队列（Story 3.6，只读，工作台待接单 Tab 轮询）。返回：
     * <ul>
     *   <li>{@code awaitingPay}：本兽医当前 {@code ACCEPTED_AWAIT_PAY} 请求（FR-53A 待支付倒计时中间态），
     *       占用互斥保证恒仅 1 单，无则 null；</li>
     *   <li>{@code available}：<b>仅兽医不忙时</b>（{@code !isBusy}）的 {@code QUEUEING} 池（FIFO），批量富化宠物身份 +
     *       机主昵称；忙（接单中/会话中）则空——不能再接。</li>
     * </ul>
     * 身份富化复用 {@link PetProfileQueryService}/{@link AccountQueryService} 只读端口（不直访 profile/auth repo，
     * 保模块边界，照 {@code VetConsultService.waitingList} 范式）。
     *
     * <p><b>V84 起含病例摘要</b>（D1）：卡片带等级/症状摘要/图数量供接单判断；完整病例（含现签图）
     * 走 {@code GET /vet/consultations/{requestToken}/case}，<b>本端点不下发签名 URL</b>。
     */
    @Transactional(readOnly = true)
    public VetQueueResponse vetQueue(long vetId) {
        VetQueueResponse.VetAwaitingPayItem awaitingPay = requests
                .findFirstByVetIdAndState(vetId, ConsultRequestState.ACCEPTED_AWAIT_PAY)
                .map(r -> VetQueueResponse.VetAwaitingPayItem.of(r, petIdentityOf(r.getUserId())))
                .orElse(null);
        List<VetQueueResponse.VetQueueItem> available = List.of();
        if (!presence.isBusy(vetId)) {
            List<ConsultRequest> pool =
                    requests.findByStateOrderByCreatedAtAsc(ConsultRequestState.QUEUEING);
            if (!pool.isEmpty()) {
                List<Long> userIds = pool.stream().map(ConsultRequest::getUserId)
                        .filter(Objects::nonNull).distinct().toList();
                Map<Long, PetIdentityView> pets = petQuery.findIdentitiesByOwners(userIds);
                Map<Long, AuthorView> authors = accounts.findAuthorViews(userIds);
                available = pool.stream()
                        .map(r -> VetQueueResponse.VetQueueItem.of(r, pets.get(r.getUserId()),
                                handleOf(authors, r.getUserId())))
                        .toList();
            }
        }
        return new VetQueueResponse(awaitingPay, available);
    }

    /**
     * 兽医查看请求病例（D1，只读，接单前判断依据）。{@code requestToken} 寻址（不可枚举）；
     * 请求不存在/已超时删/已转单删 → 404（与「无权限」同码，防枚举）。
     *
     * <p>私密图经 {@link SignedUrlService} <b>现签短 TTL URL</b>（绝不入库/落日志）。
     * 无病例 → {@code hasAiContext=false} 空响应（前端不渲染，照 {@code ConsultAiContextService} 语义）。
     *
     * <p><b>不校验兽医归属</b>——QUEUEING 池对所有不忙的在线兽医开放（抢单模型），接单前无 vet_id 可校验；
     * 与既有 {@code /vet/consult-sessions/{id}/ai-context} 的差别是本端点用不可枚举 token，无法遍历。
     */
    @Transactional(readOnly = true)
    public ConsultAiContextResponse caseOf(String requestToken) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.notFound("该请求不存在或已结束"));
        if (!req.hasCase()) {
            return ConsultAiContextResponse.empty();
        }
        List<String> refs = req.getImageObjectKeys();
        List<String> urls = (refs == null || refs.isEmpty()) ? List.of() : signedUrlService.signAll(refs);
        // aiDangerLevel 对 DIRECT 为 null（无 AI 评级），前端据此显「病例」而非「AI 上下文」标题。
        return new ConsultAiContextResponse(true, req.getAiDangerLevel(), req.getSymptomText(), urls);
    }

    /** 单条宠物身份（awaitingPay 用）：缺失/注销 → null（前端降级）。 */
    private PetIdentityView petIdentityOf(Long userId) {
        return userId == null ? null : petQuery.findIdentityByOwner(userId).orElse(null);
    }

    /** 机主昵称（注销/缺失 → null，匿名化不泄漏身份）。 */
    private static String handleOf(Map<Long, AuthorView> authors, Long userId) {
        AuthorView a = userId == null ? null : authors.get(userId);
        return a == null ? null : a.nickname();
    }

    /**
     * 兽医接单（Story 3.3，CAS，接单<b>不建会话/订单</b>）：
     * <ol>
     *   <li>占用互斥：{@code isBusy(vetId)} → 409（一兽医不占多单）；</li>
     *   <li>token 寻址：不存在 → 409（与「已被接单」同码防枚举）；</li>
     *   <li>{@code tryAccept}（H-4 单列 state CAS）：1 行=接单成功（{@code QUEUEING→ACCEPTED_AWAIT_PAY} +
     *       填 vet_id + 开 {@code pay_deadline=now+90s} 支付窗）；0 行=已被抢/已过期删 → 409。</li>
     * </ol>
     * <b>IM 会话、consult_orders 都不在此建</b>（3-4 支付成功才建）。goBusy 挂事务<b>提交后</b>
     * （{@link #afterCommit}）——CAS 回滚则 Redis 不误置 BUSY（在线态显式、最终一致，vet-presence-explicit-only）。
     */
    @Transactional
    public AcceptResult acceptRequest(long vetId, String requestToken) {
        if (presence.isBusy(vetId)) {
            throw AppException.conflict("您有进行中的接单");
        }
        // token 不存在与「已被接单」返同一 409（防枚举）。
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.conflict("该请求已被接单或已过期"));
        Instant payDeadline = Instant.now().plus(Duration.ofSeconds(PAY_WINDOW_SECONDS));
        if (requests.tryAccept(req.getId(), vetId, payDeadline) != 1) {
            throw AppException.conflict("该请求已被接单或已过期"); // 先到先得（H-4），已被抢或已删
        }
        afterCommit(() -> presence.goBusy(vetId)); // 占用兽医，提交后置 BUSY
        // 不 reload（bulk update 绕过持久化上下文，reload 读 stale）；权威值即刚写入的 state/payDeadline。
        return new AcceptResult(req.getRequestToken(), ConsultRequestState.ACCEPTED_AWAIT_PAY, payDeadline);
    }

    /**
     * 支付窗超时 → <b>结束请求</b>（bug 20260720-311，@Scheduled 调，<b>禁 MQ</b>）。逐条：
     * 读过期 {@code ACCEPTED_AWAIT_PAY}（拿 vet_id）→ {@code deleteIfState} 删 request（CAS，防已支付/已处理行）→
     * 删成功则释放兽医（{@code goAvailable}，提交后）+ 落 {@code failed_consult_requests}(TIMEOUT)。未扣费不建订单（A-5）。
     *
     * <p><b>反转 UX-DR14（原 Story 3.3/3.4 回队重播）</b>：付款超时不再回 QUEUEING 重新广播，直接终结本次请求；
     * 用户想再问诊须重新发起。因此不再读 {@code rebroadcast_count}/{@code maxRebroadcast}/{@code requestMaxAgeMinutes}。
     * 暂停中（{@code paused_at IS NOT NULL}，跳充值 A-4）请求由查询排除、不判超时。幂等可重扫。返回处理行数。
     */
    @Transactional
    public int endExpiredAcceptances() {
        // Story 3.4：排除暂停中请求（paused_at IS NOT NULL 不判超时，防跳充值暂停被扫走，A-4）。
        List<ConsultRequest> expired = requests.findByStateAndPayDeadlineAtBeforeAndPausedAtIsNull(
                ConsultRequestState.ACCEPTED_AWAIT_PAY, Instant.now());
        int handled = 0;
        for (ConsultRequest req : expired) {
            final Long vetId = req.getVetId(); // 删前捕获（删后取不到）
            int onlineVets = presence.onlineVetIds().size(); // 失败时刻在线兽医数（落库前取）
            if (requests.deleteIfState(req.getId(), ConsultRequestState.ACCEPTED_AWAIT_PAY) == 1) {
                handled++;
                if (vetId != null) {
                    afterCommit(() -> presence.goAvailable(vetId)); // 释放兽医（提交后）
                }
                // 结束落 failed_consult_requests(TIMEOUT)；不建订单（未扣费，A-5）。sessionId=0（无会话）。
                events.publishEvent(new ConsultRequestFailedEvent(
                        "TIMEOUT", req.getUserId(), 0L, req.getCreatedAt(), onlineVets));
            }
        }
        return handled;
    }

    /**
     * 跳充值暂停支付计时（Story 3.4，A-4，服务端权威）。仅本人 + {@code ACCEPTED_AWAIT_PAY} + 未暂停可暂停；
     * 暂停期间支付窗扫描跳过。归属/状态不符 → 409（与不存在同码防枚举）。
     */
    @Transactional
    public void pauseAcceptance(long userId, String requestToken) {
        ConsultRequest req = ownedAcceptedRequest(userId, requestToken);
        if (requests.pauseAcceptance(req.getId(), Instant.now()) != 1) {
            throw AppException.conflict("当前状态无法暂停支付计时");
        }
    }

    /**
     * 跳充值返回续（Story 3.4，A-4）：按剩余时间顺延 {@code pay_deadline_at}（非重置）。
     * {@code new_deadline = now + (原 pay_deadline − paused_at)}；剩余为负则置 0（立即到期，交扫描器回退）。
     */
    @Transactional
    public void resumeAcceptance(long userId, String requestToken) {
        ConsultRequest req = ownedAcceptedRequest(userId, requestToken);
        if (req.getPausedAt() == null || req.getPayDeadlineAt() == null) {
            throw AppException.conflict("支付计时未处于暂停状态");
        }
        Duration remaining = Duration.between(req.getPausedAt(), req.getPayDeadlineAt());
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        Instant newDeadline = Instant.now().plus(remaining);
        if (requests.resumeAcceptance(req.getId(), newDeadline) != 1) {
            throw AppException.conflict("支付计时未处于暂停状态");
        }
    }

    /**
     * 用户主动取消（Story 3.4，AC6）：QUEUEING 或 ACCEPTED_AWAIT_PAY 均可，物理删 request（无痕不建单，A-5）+
     * 若曾接单则释放兽医 + 落 {@code failed_consult_requests}(USER_CANCEL)。归属不符统一 409 防枚举。
     */
    @Transactional
    public void cancelRequest(long userId, String requestToken) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.conflict("该请求不存在或已处理"));
        if (req.getUserId() == null || req.getUserId() != userId) {
            throw AppException.conflict("该请求不存在或已处理");
        }
        final Long vetId = req.getVetId();
        int onlineVets = presence.onlineVetIds().size();
        // 按当前 state CAS 删（并发变态则 0 行、无操作）。
        if (requests.deleteIfState(req.getId(), req.getState()) == 1) {
            // 联动作废该用户在途 VET_CONSULT 支付意图（置 FAILED），避免 cancel 后 PENDING 残留后台支付列表。
            paymentIntents.failPending(userId, PaymentPurpose.VET_CONSULT);
            if (vetId != null) {
                afterCommit(() -> presence.goAvailable(vetId)); // 曾接单 → 释放兽医
            }
            events.publishEvent(new ConsultRequestFailedEvent(
                    "USER_CANCEL", userId, 0L, req.getCreatedAt(), onlineVets));
        }
    }

    /** 取本人 ACCEPTED_AWAIT_PAY 请求（归属/状态守卫，不符统一 409 防枚举）。 */
    private ConsultRequest ownedAcceptedRequest(long userId, String requestToken) {
        ConsultRequest req = requests.findByRequestToken(requestToken)
                .orElseThrow(() -> AppException.conflict("该请求不存在或已处理"));
        if (req.getUserId() == null || req.getUserId() != userId
                || req.getState() != ConsultRequestState.ACCEPTED_AWAIT_PAY) {
            throw AppException.conflict("该请求不存在或已处理");
        }
        return req;
    }

    /**
     * 在当前事务<b>提交后</b>执行 Redis 副作用（goBusy/goAvailable）；无活动事务时立即执行。
     * 保证：DB 状态机（权威）先落定，Redis 在线态再跟随——回滚不残留、崩溃残留由兽医重上线兜底。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

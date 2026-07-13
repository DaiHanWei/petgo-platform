package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.event.ConsultRequestQueuedForBillingEvent;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.CardTokenGenerator;
import com.tailtopia.shared.error.AppException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ConsultRequestRepository requests;
    private final PetProfileRepository petProfiles;
    private final CardTokenGenerator tokenGenerator;
    private final ApplicationEventPublisher events;

    public ConsultRequestService(ConsultRequestRepository requests, PetProfileRepository petProfiles,
            CardTokenGenerator tokenGenerator, ApplicationEventPublisher events) {
        this.requests = requests;
        this.petProfiles = petProfiles;
        this.tokenGenerator = tokenGenerator;
        this.events = events;
    }

    /** 发起结果：新建请求 or 命中已有 live 请求（alreadyActive=true，前端跳「进行中」）。 */
    public record CreateResult(ConsultRequest request, boolean alreadyActive) {
    }

    /**
     * 免费发起咨询入队。占用命中（FR-4B 同时仅 1 个）返回现有；否则建 QUEUEING（token/deadline=+1min）+ 发广播。
     * <b>绝不扣费、绝不建 consult_orders</b>。
     */
    @Transactional
    public CreateResult createRequest(long userId) {
        // 占用校验：consult_requests 内存在即 live（取消/超时已删）。
        if (requests.existsByUserId(userId)) {
            return new CreateResult(requests.findFirstByUserIdOrderByCreatedAtAsc(userId).orElseThrow(),
                    true);
        }
        // 归属：一人一宠（findByOwnerId），无档案不能发起。
        PetProfile pet = petProfiles.findByOwnerId(userId)
                .orElseThrow(() -> AppException.conflict("需先建立宠物档案后再发起问诊"));
        Instant queueDeadline = Instant.now().plus(Duration.ofSeconds(QUEUE_TIMEOUT_SECONDS));
        ConsultRequest saved = requests.save(ConsultRequest.queue(
                userId, pet.getId(), tokenGenerator.generate(), queueDeadline));
        // AFTER_COMMIT 广播在线兽医（notify 侧监听）：请求已落库才推，不吞资金写（此处非资金）。
        events.publishEvent(new ConsultRequestQueuedForBillingEvent(saved.getId()));
        return new CreateResult(saved, false);
    }

    /** 入队超时静默物理删（@Scheduled 调）。返回删除行数。不建订单、不留痕（A-5）。 */
    @Transactional
    public int purgeExpiredQueue() {
        return requests.deleteExpiredQueueing(Instant.now());
    }
}

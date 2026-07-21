package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultStageEvent;
import com.tailtopia.consult.domain.RatingPromptState;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.domain.VetDiagnosis;
import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.vet.service.VetPresenceService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话收尾 + 评分门（Story 5.6）：兽医结束 → PENDING_CLOSE；用户评分/30min 超时 → CLOSED + 存档事件。
 *
 * <p>30min 评分门是状态迁移触发器（DB 状态机 + @Scheduled 扫描，禁 MQ）。CLOSED 发 {@link ConsultClosedEvent}
 * → profile 存档落地 / IM→OSS 媒体复制（跨模块经事件，不直调对方 repository）。
 */
@Service
public class ConsultCloseService {

    /** 评分门保护窗口（秒）：30min。 */
    public static final long RATING_GATE_SECONDS = 30 * 60;

    private final ConsultSessionRepository sessions;
    private final ConsultRatingRepository ratings;
    private final VetPresenceService presence;
    private final TencentImClient imClient;
    private final ApplicationEventPublisher events;
    private final ConsultOrderRepository orders;
    private final ConsultBillingService billing;

    public ConsultCloseService(ConsultSessionRepository sessions, ConsultRatingRepository ratings,
            VetPresenceService presence, TencentImClient imClient, ApplicationEventPublisher events,
            ConsultOrderRepository orders, ConsultBillingService billing) {
        this.sessions = sessions;
        this.ratings = ratings;
        this.presence = presence;
        this.imClient = imClient;
        this.events = events;
        this.orders = orders;
        this.billing = billing;
    }

    /**
     * 兽医结束（Story 5.6 + Story C）：IN_PROGRESS → PENDING_CLOSE + 兽医 BUSY→ONLINE +
     * 定格最终诊断（{@code diagnosis}，必填由 web 层校验）+ IM 系统消息把结构化诊断推给用户。
     */
    @Transactional
    public ConsultSession endByVet(long vetId, long sessionId, VetDiagnosis diagnosis) {
        ConsultSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (s.getVetId() == null || !s.getVetId().equals(vetId)) {
            throw AppException.forbidden("无权结束该会话");
        }
        s.recordDiagnosis(diagnosis); // Story C：先定格诊断，再转 PENDING_CLOSE
        s.endByVet();
        sessions.save(s);
        presence.goAvailable(vetId); // 结束后回在线可接新单（解除 5.5 BUSY）
        if (s.getImConversationId() != null) {
            // 诊断作为系统消息推给用户（聊天里直接可见）。含健康数据：仅经 IM 投递，绝不进日志。
            imClient.sendSystemMessage(s.getImConversationId(), buildDiagnosisMessage(diagnosis));
        }
        return s;
    }

    /** 构建用户侧诊断系统消息（印尼语标签 + 兽医填写内容，跳过空字段）。 */
    private static String buildDiagnosisMessage(VetDiagnosis d) {
        StringBuilder sb = new StringBuilder("📋 Diagnosa Akhir\n");
        sb.append("Diagnosa: ").append(d.diagnosis());
        if (notBlank(d.generalAdvice())) {
            sb.append("\nSaran: ").append(d.generalAdvice());
        }
        if (d.needsMedication() && notBlank(d.medName())) {
            sb.append("\nObat: ").append(d.medName());
            if (notBlank(d.medFrequency())) {
                sb.append(" (").append(d.medFrequency()).append(')');
            }
        }
        if (notBlank(d.followUp())) {
            sb.append("\nKontrol ulang: ").append(d.followUp());
        }
        if (notBlank(d.worseningSigns())) {
            sb.append("\nWaspada: ").append(d.worseningSigns());
        }
        if (notBlank(d.clinicWithin())) {
            sb.append("\nKe klinik bila memburuk dalam: ").append(d.clinicWithin());
        }
        sb.append("\n\nSesi selesai. Mohon beri rating ya 🙏");
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 用户评分：PENDING_CLOSE → CLOSED(RATED) + 发存档事件；
     * 或补弹后对已 CLOSED(UNRATED) 会话补记评分（不重复发存档事件）。
     */
    @Transactional
    public ConsultSession submitRating(long userId, long sessionId, int stars, String comment) {
        ConsultSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (!s.getUserId().equals(userId)) {
            throw AppException.notFound("咨询不存在");
        }
        if (ratings.existsBySessionId(sessionId)) {
            throw AppException.conflict("本次会话已评分");
        }
        // 状态校验须在写评分之前：WAITING/IN_PROGRESS 会话 vet_id 可能为空，先写评分会撞约束抛 500，
        // 而非干净返回 409。仅 PENDING_CLOSE（正常评分）/ CLOSED（补弹补评分）可评分。
        SessionStatus status = s.getStatus();
        if (status != SessionStatus.PENDING_CLOSE && status != SessionStatus.CLOSED) {
            throw AppException.conflict("本次会话不在可评分状态");
        }
        String trimmed = (comment == null || comment.isBlank()) ? null : comment.trim();
        ratings.save(ConsultRating.of(sessionId, s.getVetId(), userId, stars, trimmed));

        if (status == SessionStatus.PENDING_CLOSE) {
            s.closeRated();
            sessions.save(s);
            completeBillingOrder(s); // Story 3.7：付费订单 IN_PROGRESS→COMPLETED（同事务，免费流跳过）
            publishClosed(s, true);
        } else {
            // 补弹后补评分：会话已关闭/已存档，仅清补弹标记，不重复发存档事件。
            s.clearRatingPrompt();
            sessions.save(s);
        }
        return s;
    }

    /**
     * 补弹查询：某用户待补弹评分的已关闭会话（无则空）。
     *
     * <p>AC5（F12 · R2 补评分推迟）：用户<b>有进行中会话</b>（WAITING/IN_PROGRESS/PENDING_CLOSE）时
     * <b>推迟补弹</b>——不在用户正处理活跃会话时打断（避免补弹覆盖在恢复的对话上）。补弹推迟到该活跃会话
     * 结束后再放行（届时本查询自然返回待补弹会话）。此为补评分推迟的<b>权威单点</b>，前端只消费结果。
     */
    @Transactional(readOnly = true)
    public Optional<ConsultSession> pendingRating(long userId) {
        boolean hasActive = sessions
                .findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, SessionStatus.ACTIVE)
                .isPresent();
        if (hasActive) {
            return Optional.empty(); // 推迟补弹，待活跃会话结束
        }
        return sessions.findFirstByUserIdAndStatusAndRatingPromptState(
                userId, SessionStatus.CLOSED, RatingPromptState.PENDING);
    }

    /** 补弹已展示 → 置 PROMPTED（不再弹）。 */
    @Transactional
    public void markPrompted(long userId, long sessionId) {
        ConsultSession s = sessions.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        if (!s.getUserId().equals(userId)) {
            throw AppException.notFound("咨询不存在");
        }
        s.markRatingPrompted();
        sessions.save(s);
    }

    /** 30min 超时扫描（@Scheduled 调用）：PENDING_CLOSE 超时 → CLOSED(UNRATED) + 补弹 PENDING + 存档事件。 */
    @Transactional
    public int closeExpiredGates() {
        Instant threshold = Instant.now().minusSeconds(RATING_GATE_SECONDS);
        List<ConsultSession> expired = sessions.findByStatusAndPendingCloseStartedAtBefore(
                SessionStatus.PENDING_CLOSE, threshold);
        for (ConsultSession s : expired) {
            s.closeUnrated();
            sessions.save(s);
            completeBillingOrder(s); // Story 3.7：付费订单 IN_PROGRESS→COMPLETED（同事务，免费流跳过）
            publishClosed(s, false);
        }
        return expired.size();
    }

    /**
     * 会话完成 → 付费订单完成（Story 3.7，D-1）：查该会话 {@code (userId, vetId, IN_PROGRESS)} 付费订单，
     * 有则 {@code IN_PROGRESS→COMPLETED} + 记 {@code session_ended_at}（会话终态时刻）+ 追加 {@code SESSION_ENDED}
     * 节点（append-only）。**免费直连流会话无订单 → 跳过**（不报错）。收尾事务内原子；幂等（{@code markCompleted}
     * 仅 IN_PROGRESS 生效，REFUNDING/REFUNDED 天然不匹配谓词）。
     */
    private void completeBillingOrder(ConsultSession s) {
        if (s.getVetId() == null) {
            return; // 无兽医（CLOSED 前应已接单，防御性守卫）
        }
        // bug 20260721-324：按本会话自身 consult_session_id 精确取，避免松匹配把另一场会话的收尾
        // 误算到滞留的已付款单头上。免费直连流会话无订单 → empty（跳过）。
        Optional<ConsultOrder> found = orders.findByConsultSessionIdAndStatus(
                s.getId(), ConsultOrderStatus.IN_PROGRESS);
        if (found.isEmpty()) {
            return; // 免费直连流会话无 consult_orders → 跳过
        }
        ConsultOrder order = found.get();
        Instant endedAt = s.terminalAt() != null ? s.terminalAt() : Instant.now();
        if (order.markCompleted(endedAt)) {
            orders.save(order);
            billing.appendStageEvent(order.getId(), ConsultStageEvent.SESSION_ENDED, endedAt, null);
        }
    }

    private void publishClosed(ConsultSession s, boolean rated) {
        // petId 会话不持有 → 传 null，由 profile 侧按 userId 反查（V1 单宠物）。
        // 携带事件日期(结束时点)+症状/等级/诊断摘要，供 profile 归档成 VET_CONSULT 健康事件（Bug 139）。
        VetDiagnosis d = s.getVetDiagnosis();
        Instant at = s.terminalAt();
        LocalDate eventDate =
                at != null ? LocalDate.ofInstant(at, ZoneOffset.UTC) : LocalDate.now(ZoneOffset.UTC);
        events.publishEvent(new ConsultClosedEvent(
                s.getId(), s.getUserId(), s.getVetId(), null,
                s.getImConversationId(), s.getAiImageRefs(), rated,
                eventDate, s.getAiSymptomText(), s.getAiDangerLevel(), d == null ? null : d.diagnosis()));
    }
}

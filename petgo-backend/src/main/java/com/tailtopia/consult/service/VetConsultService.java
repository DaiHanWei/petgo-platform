package com.tailtopia.consult.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.dto.ConsultAssistResponse;
import com.tailtopia.consult.dto.VetActiveItem;
import com.tailtopia.consult.dto.VetHistoryItem;
import com.tailtopia.consult.dto.VetInboxItem;
import com.tailtopia.consult.dto.VetSessionView;
import com.tailtopia.consult.event.VetRepliedEvent;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医侧咨询读取 + FR-5 辅助（Story 5.5）。待接单列表 / 会话视图 / 进行中 + 历史列表 / AI 参考回复。
 *
 * <p>宠物身份与机主昵称经跨模块只读端口富化（{@link PetProfileQueryService} / {@link AccountQueryService}），
 * 不直访 profile / auth 的 repository（保持模块边界）。注销匿名化后会话已剥 user_id → 身份兜底为 null。
 */
@Service
public class VetConsultService {

    private final ConsultSessionRepository repo;
    private final ConsultRatingRepository ratings;
    private final ConsultQueueService queue;
    private final PetProfileQueryService petProfiles;
    private final AccountQueryService accounts;
    private final ApplicationEventPublisher events;

    public VetConsultService(ConsultSessionRepository repo, ConsultRatingRepository ratings,
            ConsultQueueService queue, PetProfileQueryService petProfiles, AccountQueryService accounts,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.ratings = ratings;
        this.queue = queue;
        this.petProfiles = petProfiles;
        this.accounts = accounts;
        this.events = events;
    }

    /**
     * 兽医回复后通知用户（Story 6.2，FR-22A）。V1 触发：兽医客户端发完 IM 消息后 ping 本端点
     * （真实腾讯 IM 回调驱动属 L2）。校验兽医归属 + 会话进行中（含待关闭）→ 发 {@link VetRepliedEvent}。
     */
    @Transactional
    public void notifyReply(long vetId, long sessionId) {
        ConsultSession s = load(sessionId);
        if (s.getVetId() == null || !s.getVetId().equals(vetId)) {
            throw AppException.forbidden("无权操作该会话");
        }
        if (s.getStatus() != SessionStatus.IN_PROGRESS && s.getStatus() != SessionStatus.PENDING_CLOSE) {
            throw AppException.conflict("会话不在进行中");
        }
        events.publishEvent(new VetRepliedEvent(sessionId, s.getUserId()));
    }

    /** 待接单列表：按 Redis 队列 FIFO 取 WAITING 会话，携 AI 上下文摘要 + 宠物身份。 */
    @Transactional(readOnly = true)
    public List<VetInboxItem> waitingList() {
        return queue.waitingSessionIds().stream()
                .map(repo::findById)
                .flatMap(java.util.Optional::stream)
                .filter(s -> s.getStatus() == SessionStatus.WAITING)
                .map(s -> {
                    PetIdentityView pet = pet(s.getUserId());
                    return VetInboxItem.of(s, pet.name(), pet.species(), pet.ageMonths(), handle(s.getUserId()));
                })
                .toList();
    }

    /** 工作台「进行中」Tab：兽医活跃态（IN_PROGRESS/PENDING_CLOSE）会话卡片。 */
    @Transactional(readOnly = true)
    public List<VetActiveItem> inProgressList(long vetId) {
        return repo.findByVetIdAndStatusIn(vetId, List.of(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE))
                .stream()
                .map(s -> new VetActiveItem(s.getId(), s.getSource().name(), pet(s.getUserId()).name()))
                .toList();
    }

    /** 工作台「历史」Tab：兽医终态（CLOSED/INTERRUPTED）会话 + 评分摘要，时间倒序。 */
    @Transactional(readOnly = true)
    public List<VetHistoryItem> historyList(long vetId) {
        return repo.findByVetIdAndStatusInOrderByCreatedAtDesc(
                        vetId, List.of(SessionStatus.CLOSED, SessionStatus.INTERRUPTED))
                .stream()
                .map(this::toHistoryItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public VetSessionView sessionView(long sessionId) {
        return toSessionView(load(sessionId));
    }

    /** 富化会话视图（接单/结束/退单/读取统一出口）：补宠物身份 + 机主昵称。 */
    @Transactional(readOnly = true)
    public VetSessionView toSessionView(ConsultSession s) {
        PetIdentityView pet = pet(s.getUserId());
        return VetSessionView.of(s, pet.name(), pet.species(), pet.ageMonths(), handle(s.getUserId()));
    }

    private VetHistoryItem toHistoryItem(ConsultSession s) {
        PetIdentityView pet = pet(s.getUserId());
        ConsultRating rating = ratings.findBySessionId(s.getId()).orElse(null);
        Integer stars = rating == null ? null : rating.getStars();
        String reviewText = rating == null ? null : rating.getComment();
        return new VetHistoryItem(
                s.getId(), pet.name(), pet.species(), handle(s.getUserId()),
                terminalDate(s), stars, reviewText, s.getStatus().name(),
                s.getAiSymptomText(), s.getAiDangerLevel());
    }

    private static Instant terminalDate(ConsultSession s) {
        if (s.getInterruptedAt() != null) {
            return s.getInterruptedAt();
        }
        return s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt();
    }

    /** 宠物身份（注销匿名化或无档案 → 各字段 null）。 */
    private PetIdentityView pet(Long userId) {
        return petProfiles.findIdentityByOwner(userId)
                .orElse(new PetIdentityView(null, null, null));
    }

    /** 机主昵称（注销/缺失 → null，匿名化不泄漏身份）。 */
    private String handle(Long userId) {
        if (userId == null) {
            return null;
        }
        AuthorView v = accounts.findAuthorViews(List.of(userId)).get(userId);
        return v == null ? null : v.nickname();
    }

    /**
     * FR-5 辅助工具。AI 参考回复基于会话 AI 上下文生成（仅供参考，不自动发用户）；
     * 历史摘要冷启动空（G-2，返回空列表）。
     *
     * <p>说明：V1 冷启动用基于上下文的模板化参考回复（确定性，免外部凭证可验）；
     * 接真实 Gemini 生成更自然的参考回复属 L2 增强（{@code GeminiClient}，需 key）。
     */
    @Transactional(readOnly = true)
    public ConsultAssistResponse assist(long sessionId) {
        ConsultSession s = load(sessionId);
        return new ConsultAssistResponse(buildReferenceReply(s), List.of());
    }

    private String buildReferenceReply(ConsultSession s) {
        StringBuilder sb = new StringBuilder("您好，我已了解您的情况");
        if (s.hasAiContext()) {
            if ("YELLOW".equals(s.getAiDangerLevel())) {
                sb.append("，AI 初判需密切观察");
            }
            if (s.getAiSymptomText() != null && !s.getAiSymptomText().isBlank()) {
                sb.append("（症状：").append(s.getAiSymptomText()).append("）");
            }
        }
        sb.append("。请问最近的精神、进食和排便情况如何？方便的话再补充一张清晰照片。");
        return sb.toString();
    }

    private ConsultSession load(long sessionId) {
        return repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
    }
}

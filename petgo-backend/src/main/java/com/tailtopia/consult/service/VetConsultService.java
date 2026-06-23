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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医侧咨询读取 + FR-5 辅助（Story 5.5）。待接单列表 / 会话视图 / 进行中 + 历史列表 / AI 参考回复。
 *
 * <p>宠物身份与机主昵称经跨模块只读端口富化（{@link PetProfileQueryService} / {@link AccountQueryService}），
 * 不直访 profile / auth 的 repository（保持模块边界）。列表路径批量取身份（避免逐条 N+1）；
 * 写路径（接单/结束/退单）返回不富化的基础视图（见 {@link VetConsultController}），不把展示成本压进写延迟。
 * 注销匿名化后会话已剥 user_id → 身份兜底为 null。
 */
@Service
public class VetConsultService {

    private static final PetIdentityView EMPTY_PET = new PetIdentityView(null, null, null);

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

    /** 待接单列表：按 Redis 队列 FIFO 取 WAITING 会话，携 AI 上下文摘要 + 宠物身份（批量富化）。 */
    @Transactional(readOnly = true)
    public List<VetInboxItem> waitingList() {
        List<ConsultSession> sessions = queue.waitingSessionIds().stream()
                .map(repo::findById)
                .flatMap(java.util.Optional::stream)
                .filter(s -> s.getStatus() == SessionStatus.WAITING)
                .toList();
        Identities ids = resolveIdentities(sessions);
        return sessions.stream()
                .map(s -> {
                    PetIdentityView pet = ids.pet(s.getUserId());
                    return VetInboxItem.of(s, pet.name(), pet.species(), pet.ageMonths(), ids.handle(s.getUserId()));
                })
                .toList();
    }

    /** 工作台「进行中」Tab：兽医活跃态（IN_PROGRESS/PENDING_CLOSE）会话卡片（批量富化宠物名）。 */
    @Transactional(readOnly = true)
    public List<VetActiveItem> inProgressList(long vetId) {
        List<ConsultSession> sessions = repo.findByVetIdAndStatusIn(
                vetId, List.of(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE));
        Identities ids = resolveIdentities(sessions);
        return sessions.stream()
                .map(s -> new VetActiveItem(s.getId(), s.getSource().name(), ids.pet(s.getUserId()).name(),
                        ids.handle(s.getUserId()), ids.avatar(s.getUserId())))
                .toList();
    }

    /** 工作台「历史」Tab：兽医终态（CLOSED/INTERRUPTED）会话 + 评分摘要，按终态时间倒序（批量富化）。 */
    @Transactional(readOnly = true)
    public List<VetHistoryItem> historyList(long vetId) {
        List<ConsultSession> sessions = repo.findByVetIdAndStatusInOrderByCreatedAtDesc(
                vetId, List.of(SessionStatus.CLOSED, SessionStatus.INTERRUPTED));
        Identities ids = resolveIdentities(sessions);
        // 评分批量取（一次拿全该兽医的评分，按 sessionId 建 Map），避免逐条 findBySessionId。
        Map<Long, ConsultRating> ratingBySession = ratings.findByVetIdOrderByCreatedAtDesc(vetId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConsultRating::getSessionId, r -> r, (a, b) -> a));
        return sessions.stream()
                .map(s -> toHistoryItem(s, ids, ratingBySession.get(s.getId())))
                // 排序键与展示键统一为 terminalAt，避免列表顺序与卡片日期打架。
                .sorted(Comparator.comparing(VetHistoryItem::date).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public VetSessionView sessionView(long sessionId) {
        return toSessionView(load(sessionId));
    }

    /** 富化单条会话视图（读路径 sessionView 用）：补宠物身份 + 机主昵称。 */
    @Transactional(readOnly = true)
    public VetSessionView toSessionView(ConsultSession s) {
        PetIdentityView pet = petProfiles.findIdentityByOwner(s.getUserId()).orElse(EMPTY_PET);
        return VetSessionView.of(s, pet.name(), pet.species(), pet.ageMonths(), handle(s.getUserId()));
    }

    private VetHistoryItem toHistoryItem(ConsultSession s, Identities ids, ConsultRating rating) {
        PetIdentityView pet = ids.pet(s.getUserId());
        Integer stars = rating == null ? null : rating.getStars();
        String reviewText = rating == null ? null : rating.getComment();
        return new VetHistoryItem(
                s.getId(), pet.name(), pet.species(), ids.handle(s.getUserId()),
                s.terminalAt(), stars, reviewText, s.getStatus().name(),
                s.getAiSymptomText(), s.getAiDangerLevel());
    }

    /** 批量解析一组会话的宠物身份 + 机主昵称（去重 userId 各查一次，杜绝逐条 N+1）。 */
    private Identities resolveIdentities(List<ConsultSession> sessions) {
        List<Long> userIds = sessions.stream()
                .map(ConsultSession::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return new Identities(Map.of(), Map.of());
        }
        return new Identities(petProfiles.findIdentitiesByOwners(userIds), accounts.findAuthorViews(userIds));
    }

    /** 机主昵称（单条 sessionView 用；注销/缺失 → null，匿名化不泄漏身份）。 */
    private String handle(Long userId) {
        if (userId == null) {
            return null;
        }
        AuthorView v = accounts.findAuthorViews(List.of(userId)).get(userId);
        return v == null ? null : v.nickname();
    }

    /** 批量富化结果持有（按 userId 取，缺失兜底空身份 / null 昵称）。 */
    private record Identities(Map<Long, PetIdentityView> pets, Map<Long, AuthorView> authors) {
        PetIdentityView pet(Long userId) {
            PetIdentityView p = userId == null ? null : pets.get(userId);
            return p != null ? p : EMPTY_PET;
        }

        String handle(Long userId) {
            AuthorView a = userId == null ? null : authors.get(userId);
            return a == null ? null : a.nickname();
        }

        /** 机主头像 URL（注销/未设 → null，前端降级首字母）。 */
        String avatar(Long userId) {
            AuthorView a = userId == null ? null : authors.get(userId);
            return a == null ? null : a.avatarUrl();
        }
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
        StringBuilder sb = new StringBuilder("Hello, I've reviewed your case");
        if (s.hasAiContext()) {
            if ("YELLOW".equals(s.getAiDangerLevel())) {
                sb.append(". The AI pre-assessment flags it for close monitoring");
            }
            if (s.getAiSymptomText() != null && !s.getAiSymptomText().isBlank()) {
                sb.append(" (symptoms: ").append(s.getAiSymptomText()).append(")");
            }
        }
        sb.append(". How have your pet's energy, appetite, and bowel movements been lately? "
                + "If possible, please share a clear photo.");
        return sb.toString();
    }

    private ConsultSession load(long sessionId) {
        return repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
    }
}

package com.tailtopia.consult.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.domain.VetDiagnosis;
import com.tailtopia.consult.dto.ConsultAssistResponse;
import com.tailtopia.consult.dto.VetActiveItem;
import com.tailtopia.consult.dto.VetHistoryItem;
import com.tailtopia.consult.dto.VetInboxItem;
import com.tailtopia.consult.dto.VetSessionView;
import com.tailtopia.consult.event.VetRepliedEvent;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.error.AppException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final ConsultOrderRepository orders;

    public VetConsultService(ConsultSessionRepository repo, ConsultRatingRepository ratings,
            ConsultQueueService queue, PetProfileQueryService petProfiles, AccountQueryService accounts,
            ApplicationEventPublisher events, ConsultOrderRepository orders) {
        this.repo = repo;
        this.ratings = ratings;
        this.queue = queue;
        this.petProfiles = petProfiles;
        this.accounts = accounts;
        this.events = events;
        this.orders = orders;
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
                // bug 20260803：弃单（用户超时离开未续期）不进收件箱，与接单守卫同口径。
                .filter(s -> !s.isWaitingAbandoned(ConsultSessionService.WAITING_ABANDON_SECONDS))
                .toList();
        // WAITING 会话是 V1.0 免费发起路径，没有订单 → 宠物回落默认宠物（V1.4.0 multi-pet Story 1.2 · X5）。
        Identities ids = resolveIdentities(sessions, Map.of());
        return sessions.stream()
                .map(s -> {
                    PetIdentityView pet = ids.pet(s);
                    return VetInboxItem.of(s, pet.name(), pet.species(), pet.ageMonths(), ids.handle(s.getUserId()));
                })
                .toList();
    }

    /**
     * 工作台「进行中」Tab：兽医活跃态会话卡片（批量富化宠物名）。
     *
     * <p><b>仅 {@code IN_PROGRESS}</b>——与 {@link SessionStatus#ACTIVE} 口径一致：兽医结束后 PENDING_CLOSE
     * 不再算「进行中」（兽医侧已无法续聊：会话页仅 IN_PROGRESS 登录 IM、结束钮也仅 IN_PROGRESS 显示），
     * 归入 {@link #historyList}。若仍含 PENDING_CLOSE，会与「用户结束后名额释放、可发起新咨询」叠加 ——
     * 同一用户同时出现 PENDING_CLOSE + 新 IN_PROGRESS 两张卡（重复 item bug）。
     */
    @Transactional(readOnly = true)
    public List<VetActiveItem> inProgressList(long vetId) {
        List<ConsultSession> sessions = repo.findByVetIdAndStatusIn(
                vetId, List.of(SessionStatus.IN_PROGRESS));
        Identities ids = resolveIdentities(sessions, ordersBySession(vetId, sessions));
        return sessions.stream()
                .map(s -> new VetActiveItem(s.getId(), s.getUserId(), s.getSource().name(),
                        ids.pet(s).name(), ids.handle(s.getUserId()), ids.avatar(s.getUserId()),
                        com.tailtopia.shared.im.ImAccountMapper.userImId(s.getUserId())))
                .toList();
    }

    /**
     * 工作台「历史」Tab：兽医已结束会话 + 评分摘要，按终态时间倒序（批量富化）。
     *
     * <p>含 {@code PENDING_CLOSE}（兽医已提交诊断、30min 评分门窗口）——与用户侧
     * {@code ConsultHistoryService} 口径一致：结束即归历史、不再占「进行中」。兽医侧此态只读
     * （诊断已定格、无法续聊），停在历史供回看。
     */
    @Transactional(readOnly = true)
    public List<VetHistoryItem> historyList(long vetId) {
        List<ConsultSession> sessions = repo.findByVetIdAndStatusInOrderByCreatedAtDesc(
                vetId, List.of(SessionStatus.PENDING_CLOSE, SessionStatus.CLOSED, SessionStatus.INTERRUPTED));
        // 评分批量取（一次拿全该兽医的评分，按 sessionId 建 Map），避免逐条 findBySessionId。
        Map<Long, ConsultRating> ratingBySession = ratings.findByVetIdOrderByCreatedAtDesc(vetId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConsultRating::getSessionId, r -> r, (a, b) -> a));
        // V88（ref36 到手金额）：按 (vetId, 会话 id 批) 批量取订单，建 sessionId→order Map（避免 N+1）。
        // 迁移前订单 consult_session_id 为 null → 不入 Map，老历史到手金额为 null（前端显「—」）。
        Map<Long, ConsultOrder> orderBySession = ordersBySession(vetId, sessions);
        // V1.4.0 multi-pet（Story 1.2 · X5）：宠物身份按会话的订单取（复用同一批订单，不再多查）。
        Identities ids = resolveIdentities(sessions, orderBySession);
        return sessions.stream()
                .map(s -> toHistoryItem(s, ids, ratingBySession.get(s.getId()), orderBySession.get(s.getId())))
                // 排序键与展示键统一为 terminalAt，避免列表顺序与卡片日期打架。
                .sorted(Comparator.comparing(VetHistoryItem::date).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public VetSessionView sessionView(long sessionId) {
        return toSessionView(load(sessionId));
    }

    /**
     * 兽医查看自己接诊会话的最终诊断（Story C 收尾 · 工作台「历史」卡 View 入口）。
     *
     * <p>归属校验套兽医侧现成模式（参照 {@link #notifyReply}）：仅本会话接诊兽医可查，
     * 非归属 → 403（不越权看他人病例）。未出诊断（如 INTERRUPTED 未提交）→ null，controller 转 204。
     * 诊断为健康数据：仅按需返回，绝不进日志（访问日志层已对 {@code diagnosis} 字段脱敏）。
     */
    @Transactional(readOnly = true)
    public VetDiagnosis diagnosisForVet(long vetId, long sessionId) {
        ConsultSession s = load(sessionId);
        if (s.getVetId() == null || !s.getVetId().equals(vetId)) {
            throw AppException.forbidden("无权查看该会话");
        }
        return s.getVetDiagnosis();
    }

    /** 富化单条会话视图（读路径 sessionView 用）：补宠物身份 + 机主昵称。 */
    @Transactional(readOnly = true)
    public VetSessionView toSessionView(ConsultSession s) {
        PetIdentityView pet = sessionPet(s);
        return VetSessionView.of(s, pet.name(), pet.species(), pet.ageMonths(), handle(s.getUserId()));
    }

    private VetHistoryItem toHistoryItem(ConsultSession s, Identities ids, ConsultRating rating,
            ConsultOrder order) {
        PetIdentityView pet = ids.pet(s);
        Integer stars = rating == null ? null : rating.getStars();
        String reviewText = rating == null ? null : rating.getComment();
        // V88：到手金额取订单 vet_payout 快照（无关联订单→null）；退款态（REFUNDING/REFUNDED）前端显 Rp0 删除线。
        Long payoutAmount = order == null ? null : order.getVetPayout();
        boolean refunded = order != null
                && (order.getStatus() == ConsultOrderStatus.REFUNDED
                        || order.getStatus() == ConsultOrderStatus.REFUNDING);
        return new VetHistoryItem(
                s.getId(), pet.name(), pet.species(), ids.handle(s.getUserId()),
                s.terminalAt(), stars, reviewText, s.getStatus().name(),
                s.getAiSymptomText(), s.getAiDangerLevel(),
                s.getSource().name(), payoutAmount, refunded);
    }

    /**
     * 单条会话的宠物身份（V1.4.0 multi-pet Story 1.2 · X4，问诊按单上的宠物）：会话最近一张订单的
     * {@code pet_profile_id} → 该宠物；无订单（V1.0 免费会话）→ 机主默认宠物；都没有 / 单上宠物已删 → 空身份。
     * 注销匿名化（user_id 已剥）→ 空身份。
     */
    private PetIdentityView sessionPet(ConsultSession s) {
        if (s.getUserId() == null) {
            return EMPTY_PET;
        }
        Optional<ConsultOrder> order = s.getId() == null
                ? Optional.empty()
                : orders.findFirstByConsultSessionIdOrderByIdDesc(s.getId());
        if (order.isPresent() && order.get().getPetProfileId() != null) {
            return petProfiles.findIdentityByPetId(order.get().getPetProfileId()).orElse(EMPTY_PET);
        }
        return petProfiles.findDefaultIdentityByOwner(s.getUserId()).orElse(EMPTY_PET);
    }

    /** 按 (vetId, 会话 id 批) 批量取订单，建 sessionId→order Map（避免 N+1）。无 consult_session_id 的老订单不入 Map。 */
    private Map<Long, ConsultOrder> ordersBySession(long vetId, List<ConsultSession> sessions) {
        List<Long> sessionIds = sessions.stream().map(ConsultSession::getId).filter(Objects::nonNull).toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return orders.findByVetIdAndConsultSessionIdIn(vetId, sessionIds).stream()
                .filter(o -> o.getConsultSessionId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        ConsultOrder::getConsultSessionId, o -> o, (a, b) -> a));
    }

    /**
     * 批量解析一组会话的宠物身份 + 机主昵称（杜绝逐条 N+1）。
     *
     * <p>V1.4.0 multi-pet（Story 1.2 · X5）：宠物身份按<b>会话</b>取——有订单的按单上宠物
     * （{@code findIdentitiesByPetIds} 一次），无订单的按机主默认宠物（{@code findDefaultIdentitiesByOwners} 一次）；
     * 单上宠物已删 → 空身份兜底，不抛。
     */
    private Identities resolveIdentities(List<ConsultSession> sessions, Map<Long, ConsultOrder> orderBySession) {
        List<Long> userIds = sessions.stream()
                .map(ConsultSession::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return new Identities(Map.of(), Map.of());
        }
        Map<Long, Long> petIdBySession = new HashMap<>();
        java.util.Set<Long> fallbackOwners = new java.util.HashSet<>();
        for (ConsultSession s : sessions) {
            if (s.getUserId() == null) {
                continue;
            }
            ConsultOrder o = s.getId() == null ? null : orderBySession.get(s.getId());
            if (o != null && o.getPetProfileId() != null) {
                petIdBySession.put(s.getId(), o.getPetProfileId());
            } else {
                fallbackOwners.add(s.getUserId());
            }
        }
        Map<Long, PetIdentityView> byPet = petIdBySession.isEmpty()
                ? Map.of()
                : petProfiles.findIdentitiesByPetIds(petIdBySession.values());
        Map<Long, PetIdentityView> byOwner = fallbackOwners.isEmpty()
                ? Map.of()
                : petProfiles.findDefaultIdentitiesByOwners(fallbackOwners);
        Map<Long, PetIdentityView> petBySession = new HashMap<>();
        for (ConsultSession s : sessions) {
            if (s.getUserId() == null || s.getId() == null) {
                continue;
            }
            Long petId = petIdBySession.get(s.getId());
            PetIdentityView v = petId != null ? byPet.get(petId) : byOwner.get(s.getUserId());
            if (v != null) {
                petBySession.put(s.getId(), v);
            }
        }
        return new Identities(petBySession, accounts.findAuthorViews(userIds));
    }

    /** 机主昵称（单条 sessionView 用；注销/缺失 → null，匿名化不泄漏身份）。 */
    private String handle(Long userId) {
        if (userId == null) {
            return null;
        }
        AuthorView v = accounts.findAuthorViews(List.of(userId)).get(userId);
        return v == null ? null : v.nickname();
    }

    /** 批量富化结果持有（宠物按会话 id 取、昵称按 userId 取；缺失兜底空身份 / null 昵称）。 */
    private record Identities(Map<Long, PetIdentityView> petBySession, Map<Long, AuthorView> authors) {
        PetIdentityView pet(ConsultSession s) {
            PetIdentityView p = s.getId() == null ? null : petBySession.get(s.getId());
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

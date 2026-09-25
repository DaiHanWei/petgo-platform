package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.dto.ConsultHistoryItem;
import com.tailtopia.consult.dto.ConsultHistoryPage;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.triage.dto.TriageHistoryItem;
import com.tailtopia.triage.service.TriageService;
import com.tailtopia.vet.service.VetAccountService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问诊历史聚合（Story 5.8）：AI 分诊（经 {@link TriageService}）+ 兽医咨询（consult_sessions + ratings）
 * 混排倒序，游标分页。<b>跨模块经 service 接口</b>（不直读 triage repository）。历史<b>独立于存档</b>。
 */
@Service
public class ConsultHistoryService {

    private final ConsultSessionRepository sessions;
    private final ConsultRatingRepository ratings;
    private final TriageService triageService;
    private final VetAccountService vetAccounts;
    // 直接用仓储而不是 HealthEventService：后者挂着 profile 一串服务，consult→profile 服务层互引有循环风险
    private final HealthEventRepository healthEvents;

    public ConsultHistoryService(ConsultSessionRepository sessions, ConsultRatingRepository ratings,
            TriageService triageService, VetAccountService vetAccounts,
            HealthEventRepository healthEvents) {
        this.healthEvents = healthEvents;
        this.sessions = sessions;
        this.ratings = ratings;
        this.triageService = triageService;
        this.vetAccounts = vetAccounts;
    }

    /**
     * 后台用户详情（Story 3.1）：某用户兽医问诊会话**仅元数据**（会话 id/兽医/起止/状态/评分），
     * createdAt 倒序。<b>绝不投影 aiImageRefs/症状文本/IM 正文</b>（数据边界 AG-6）。
     */
    @Transactional(readOnly = true)
    public List<SessionMeta> adminSessionMetadata(long userId) {
        return sessions.findByUserId(userId).stream()
                .sorted(Comparator.comparing(ConsultSession::getCreatedAt).reversed())
                .map(s -> new SessionMeta(s.getId(), s.getVetId(), s.getStatus().name(),
                        s.getCreatedAt(), s.terminalAt(),
                        ratings.findBySessionId(s.getId()).map(ConsultRating::getStars).orElse(null)))
                .toList();
    }

    /** 后台会话元数据投影（不含任何对话内容/AI 上下文/媒体）。 */
    public record SessionMeta(long sessionId, Long vetId, String status,
            java.time.Instant createdAt, java.time.Instant endedAt, Integer stars) {
    }

    /**
     * 聚合历史。{@code cursor}=上一页末条 epochMillis（首页 null）；返回 {@code limit} 条 + nextCursor。
     * V1 低量：内存合并两源 + 游标过滤（架构禁 MQ/缓存，单机直查）。
     */
    @Transactional(readOnly = true)
    public ConsultHistoryPage history(long userId, String cursor, int limit) {
        List<ConsultHistoryItem> all = new ArrayList<>();

        List<TriageHistoryItem> triages = triageService.historyForUser(userId);
        // PENDING_CLOSE(兽医已结束、30min 续聊窗口) 也归入历史,带 terminalState=PENDING_CLOSE 标记——
        // 不再算「进行中」(见 SessionStatus.ACTIVE);用户从历史进入仍可在窗口内续聊。
        List<ConsultSession> vetSessions = sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(userId,
                List.of(SessionStatus.PENDING_CLOSE, SessionStatus.CLOSED, SessionStatus.INTERRUPTED));

        // 🔴 bug 20260721-340：archived 取真实存档状态（原先写死 false，卡片上的「已归档」永远不出现）。
        //    sourceRef 口径与存档写入端一致：兽医 consult:<sessionId>、AI triage:<triageId>
        //    （同 ConsultSessionController 结果页的 isArchived 判定）。一次批量查。
        Set<String> refs = new HashSet<>();
        triages.forEach(t -> refs.add(TRIAGE_REF + t.triageId()));
        vetSessions.forEach(s -> refs.add(CONSULT_REF + s.getId()));
        Set<String> archived = refs.isEmpty() ? Set.of()
                : new HashSet<>(healthEvents.findSourceRefsByDecision(refs, ArchiveDecision.ARCHIVED));

        for (TriageHistoryItem t : triages) {
            all.add(ConsultHistoryItem.ai(t.triageId(), t.dangerLevel(), t.symptomSummary(),
                    archived.contains(TRIAGE_REF + t.triageId()), t.date()));
        }
        for (ConsultSession s : vetSessions) {
            all.add(toVetItem(s, archived.contains(CONSULT_REF + s.getId())));
        }

        // 倒序混排
        all.sort(Comparator.comparing(ConsultHistoryItem::date).reversed());

        // 游标过滤（date < cursor）
        long cursorMillis = parseCursor(cursor);
        List<ConsultHistoryItem> filtered = all.stream()
                .filter(i -> i.date() != null && i.date().toEpochMilli() < cursorMillis)
                .toList();

        boolean hasMore = filtered.size() > limit;
        List<ConsultHistoryItem> page = filtered.stream().limit(limit).toList();
        String nextCursor = hasMore && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).date().toEpochMilli())
                : null;
        return new ConsultHistoryPage(page, nextCursor, hasMore);
    }

    private static final String CONSULT_REF = "consult:";
    private static final String TRIAGE_REF = "triage:";

    private ConsultHistoryItem toVetItem(ConsultSession s, boolean archived) {
        String vetName = s.getVetId() == null ? null
                : safeVetName(s.getVetId());
        Integer stars = ratings.findBySessionId(s.getId()).map(ConsultRating::getStars).orElse(null);
        String summary = s.getAiSymptomText(); // V1：用 AI 上下文症状作摘要（DIRECT 无则 null）
        String closedReason = s.getClosedReason() == null ? null : s.getClosedReason().name();
        String interruptedReason = s.getInterruptedReason() == null ? null : s.getInterruptedReason().name();
        return ConsultHistoryItem.vet(s.getId(), vetName, summary, stars, archived,
                s.getStatus().name(), closedReason, interruptedReason, s.terminalAt());
    }

    private String safeVetName(long vetId) {
        try {
            return vetAccounts.getById(vetId).getDisplayName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}

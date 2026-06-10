package com.petgo.consult.service;

import com.petgo.consult.domain.ConsultRating;
import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.dto.ConsultHistoryItem;
import com.petgo.consult.dto.ConsultHistoryPage;
import com.petgo.consult.repository.ConsultRatingRepository;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.triage.dto.TriageHistoryItem;
import com.petgo.triage.service.TriageService;
import com.petgo.vet.service.VetAccountService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public ConsultHistoryService(ConsultSessionRepository sessions, ConsultRatingRepository ratings,
            TriageService triageService, VetAccountService vetAccounts) {
        this.sessions = sessions;
        this.ratings = ratings;
        this.triageService = triageService;
        this.vetAccounts = vetAccounts;
    }

    /**
     * 聚合历史。{@code cursor}=上一页末条 epochMillis（首页 null）；返回 {@code limit} 条 + nextCursor。
     * V1 低量：内存合并两源 + 游标过滤（架构禁 MQ/缓存，单机直查）。
     */
    @Transactional(readOnly = true)
    public ConsultHistoryPage history(long userId, String cursor, int limit) {
        List<ConsultHistoryItem> all = new ArrayList<>();

        for (TriageHistoryItem t : triageService.historyForUser(userId)) {
            all.add(ConsultHistoryItem.ai(t.triageId(), t.dangerLevel(), t.symptomSummary(), t.date()));
        }
        for (ConsultSession s : sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of(SessionStatus.CLOSED, SessionStatus.INTERRUPTED))) {
            all.add(toVetItem(s));
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

    private ConsultHistoryItem toVetItem(ConsultSession s) {
        String vetName = s.getVetId() == null ? null
                : safeVetName(s.getVetId());
        Integer stars = ratings.findBySessionId(s.getId()).map(ConsultRating::getStars).orElse(null);
        String summary = s.getAiSymptomText(); // V1：用 AI 上下文症状作摘要（DIRECT 无则 null）
        String closedReason = s.getClosedReason() == null ? null : s.getClosedReason().name();
        String interruptedReason = s.getInterruptedReason() == null ? null : s.getInterruptedReason().name();
        // archived 标记位：FR-16 存档落地在 Epic 2 profile，本故事暂为 false（历史独立于存档）。
        return ConsultHistoryItem.vet(s.getId(), vetName, summary, stars, false,
                s.getStatus().name(), closedReason, interruptedReason, terminalDate(s));
    }

    private static Instant terminalDate(ConsultSession s) {
        if (s.getInterruptedAt() != null) {
            return s.getInterruptedAt();
        }
        return s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt();
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

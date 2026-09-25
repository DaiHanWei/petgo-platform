package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.dto.ConsultHistoryPage;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.dto.TriageHistoryItem;
import com.tailtopia.triage.service.TriageService;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.service.VetAccountService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试：AI + 兽医两类历史混排倒序、兽医条目带评分/兽医名；
 * archived 取真实存档状态（bug 20260721-340，原先写死 false）。
 */
@ExtendWith(MockitoExtension.class)
class ConsultHistoryServiceTest {

    @Mock
    ConsultSessionRepository sessions;
    @Mock
    ConsultRatingRepository ratings;
    @Mock
    TriageService triageService;
    @Mock
    VetAccountService vetAccounts;
    @Mock
    HealthEventRepository healthEvents;

    private ConsultHistoryService service() {
        return new ConsultHistoryService(sessions, ratings, triageService, vetAccounts, healthEvents);
    }

    private ConsultSession closedVetSession(long id, Instant created) {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "id", id);
        ReflectionTestUtils.setField(s, "createdAt", created);
        ReflectionTestUtils.setField(s, "updatedAt", created);
        s.markInProgress(3L);
        s.endByVet();
        s.closeRated();
        return s;
    }

    @Test
    void aggregatesAiAndVetHistorySortedDesc() {
        Instant older = Instant.parse("2026-06-01T00:00:00Z");
        Instant newer = Instant.parse("2026-06-02T00:00:00Z");
        when(triageService.historyForUser(7L)).thenReturn(
                List.of(new TriageHistoryItem(1L, DangerLevel.GREEN.name(), "继续观察", older)));
        when(sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(List.of(closedVetSession(11L, newer)));
        when(ratings.findBySessionId(11L)).thenReturn(
                Optional.of(ConsultRating.of(11L, 3L, 7L, 5, "很专业")));
        VetAccount vet = VetAccount.create("王医生", "$2a$10$x", "王医生");
        when(vetAccounts.getById(3L)).thenReturn(vet);

        ConsultHistoryPage page = service().history(7L, null, 20);

        assertThat(page.items()).hasSize(2);
        // 最新（兽医，newer）在前
        assertThat(page.items().get(0).type()).isEqualTo("VET");
        assertThat(page.items().get(0).userStars()).isEqualTo(5);
        assertThat(page.items().get(0).vetDisplayName()).isEqualTo("王医生");
        assertThat(page.items().get(0).archived()).isFalse(); // 没存档 → false（历史独立于存档）
        assertThat(page.items().get(1).type()).isEqualTo("AI");
        assertThat(page.items().get(1).dangerLevel()).isEqualTo("GREEN");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void archivedReflectsRealArchiveDecisionForBothVetAndAi() {
        Instant t = Instant.parse("2026-06-02T00:00:00Z");
        when(triageService.historyForUser(7L)).thenReturn(List.of(
                new TriageHistoryItem(1L, "GREEN", "已存档的 AI", t.minusSeconds(10)),
                new TriageHistoryItem(2L, "YELLOW", "没存档的 AI", t.minusSeconds(20))));
        when(sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(List.of(closedVetSession(11L, t)));
        when(vetAccounts.getById(3L)).thenReturn(VetAccount.create("王医生", "$2a$10$x", "王医生"));
        // sourceRef 口径：兽医 consult:<sessionId>、AI triage:<triageId>
        when(healthEvents.findSourceRefsByDecision(any(), org.mockito.ArgumentMatchers.eq(ArchiveDecision.ARCHIVED)))
                .thenReturn(List.of("consult:11", "triage:1"));

        ConsultHistoryPage page = service().history(7L, null, 20);

        assertThat(page.items()).extracting(i -> i.type() + ":" + i.archived())
                .containsExactly("VET:true", "AI:true", "AI:false");
    }

    @Test
    void respectsLimitAndEmitsCursor() {
        Instant base = Instant.parse("2026-06-02T00:00:00Z");
        when(triageService.historyForUser(7L)).thenReturn(List.of(
                new TriageHistoryItem(1L, "GREEN", "a", base.minusSeconds(10)),
                new TriageHistoryItem(2L, "YELLOW", "b", base.minusSeconds(20)),
                new TriageHistoryItem(3L, "GREEN", "c", base.minusSeconds(30))));
        when(sessions.findByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any())).thenReturn(List.of());

        ConsultHistoryPage page = service().history(7L, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotNull();
    }
}

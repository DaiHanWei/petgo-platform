package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.dto.VetQualitySummary;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：兽医评分质量聚合（Story 6.1）——已评/未评口径 + 均分 + 日期窗（会话 created_at 单一口径）。 */
class ConsultQualityQueryServiceTest {

    private ConsultSessionRepository sessions;
    private ConsultRatingRepository ratings;
    private ConsultQualityQueryService service;

    @BeforeEach
    void setUp() {
        sessions = mock(ConsultSessionRepository.class);
        ratings = mock(ConsultRatingRepository.class);
        service = new ConsultQualityQueryService(sessions, ratings);
    }

    private ConsultSession session(long id, Instant createdAt) {
        ConsultSession s = mock(ConsultSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getCreatedAt()).thenReturn(createdAt);
        return s;
    }

    private ConsultRating rating(int stars) {
        ConsultRating r = mock(ConsultRating.class);
        when(r.getStars()).thenReturn(stars);
        return r;
    }

    @Test
    void countsRatedUnratedAndAverages() {
        ConsultSession s1 = session(1L, Instant.parse("2026-06-01T00:00:00Z"));
        ConsultSession s2 = session(2L, Instant.parse("2026-06-02T00:00:00Z"));
        ConsultSession s3 = session(3L, Instant.parse("2026-06-03T00:00:00Z"));
        when(sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(eq(200L), any()))
                .thenReturn(List.of(s1, s2, s3));
        ConsultRating r4 = rating(4);
        ConsultRating r5 = rating(5);
        when(ratings.findBySessionId(1L)).thenReturn(Optional.of(r4));
        when(ratings.findBySessionId(2L)).thenReturn(Optional.of(r5));
        when(ratings.findBySessionId(3L)).thenReturn(Optional.empty()); // CLOSED 无评分 → 未评

        VetQualitySummary q = service.qualitySummary(200L, null, null);

        assertThat(q.ratedCount()).isEqualTo(2);
        assertThat(q.unratedCount()).isEqualTo(1);
        assertThat(q.average()).isEqualTo(4.5);
    }

    @Test
    void dateWindowFiltersBySessionCreatedAt() {
        ConsultSession outOfWindow = session(1L, Instant.parse("2026-05-01T00:00:00Z"));
        ConsultSession inWindow = session(2L, Instant.parse("2026-06-15T00:00:00Z"));
        when(sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(List.of(outOfWindow, inWindow));
        ConsultRating r3 = rating(3);
        when(ratings.findBySessionId(2L)).thenReturn(Optional.of(r3));

        VetQualitySummary q = service.qualitySummary(200L,
                Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(q.ratedCount()).isEqualTo(1);
        assertThat(q.unratedCount()).isZero();
        assertThat(q.average()).isEqualTo(3.0);
    }

    @Test
    void noSessionsYieldsZeroes() {
        when(sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(anyLong(), any())).thenReturn(List.of());
        VetQualitySummary q = service.qualitySummary(200L, null, null);
        assertThat(q.ratedCount()).isZero();
        assertThat(q.unratedCount()).isZero();
        assertThat(q.average()).isZero();
    }

    // ===== Story 6.2：未评问诊列表 + 原因映射 =====

    private ConsultSession terminalSession(long id, com.tailtopia.consult.domain.SessionStatus status) {
        ConsultSession s = mock(ConsultSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getStatus()).thenReturn(status);
        when(s.terminalAt()).thenReturn(Instant.parse("2026-06-01T01:00:00Z"));
        return s;
    }

    @Test
    void unratedListsExcludesRatedAndMapsReasons() {
        ConsultSession rated = terminalSession(1L, com.tailtopia.consult.domain.SessionStatus.CLOSED);
        ConsultSession timeout = terminalSession(2L, com.tailtopia.consult.domain.SessionStatus.CLOSED);
        ConsultSession interrupted = terminalSession(3L, com.tailtopia.consult.domain.SessionStatus.INTERRUPTED);
        when(sessions.findByVetIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(List.of(rated, timeout, interrupted));
        when(ratings.existsBySessionId(1L)).thenReturn(true);  // 已评（含 UNRATED 后补评）→ 排除
        when(ratings.existsBySessionId(2L)).thenReturn(false); // CLOSED 无评分 → 超时未评
        when(ratings.existsBySessionId(3L)).thenReturn(false); // 中断未评

        var rows = service.unratedConsults(200L);

        assertThat(rows).hasSize(2);
        assertThat(rows).noneMatch(r -> r.sessionId() == 1L);
        assertThat(rows.stream().filter(r -> r.sessionId() == 2L).findFirst().orElseThrow().reason())
                .isEqualTo(com.tailtopia.consult.dto.UnratedReason.TIMEOUT_UNRATED);
        assertThat(rows.stream().filter(r -> r.sessionId() == 3L).findFirst().orElseThrow().reason())
                .isEqualTo(com.tailtopia.consult.dto.UnratedReason.INTERRUPTED);
    }
}

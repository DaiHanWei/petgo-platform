package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.dto.ConsultSessionMetaRow;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** L0：会话元数据只读查询（Story 5.2）——元数据 + 评分映射；空 userId 容错；NFR5 仅元数据。 */
class ConsultSessionAdminQueryServiceTest {

    private ConsultSessionRepository sessions;
    private ConsultRatingRepository ratings;
    private ConsultSessionAdminQueryService service;

    @BeforeEach
    void setUp() {
        sessions = mock(ConsultSessionRepository.class);
        ratings = mock(ConsultRatingRepository.class);
        service = new ConsultSessionAdminQueryService(sessions, ratings);
    }

    @SuppressWarnings("unchecked")
    private ConsultSession session(long id, Long userId) {
        ConsultSession s = mock(ConsultSession.class);
        when(s.getId()).thenReturn(id);
        when(s.getUserId()).thenReturn(userId);
        when(s.getVetId()).thenReturn(200L);
        when(s.getCreatedAt()).thenReturn(Instant.parse("2026-06-01T00:00:00Z"));
        when(s.terminalAt()).thenReturn(Instant.parse("2026-06-01T01:00:00Z"));
        when(s.getStatus()).thenReturn(SessionStatus.CLOSED);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsMetadataWithAndWithoutRating() {
        ConsultSession rated = session(1L, 42L);
        ConsultSession unrated = session(2L, null); // 匿名化后 userId 空
        when(sessions.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(rated, unrated));
        ConsultRating r = mock(ConsultRating.class);
        when(r.getStars()).thenReturn(5);
        when(r.getComment()).thenReturn("很好");
        when(ratings.findBySessionId(1L)).thenReturn(Optional.of(r));
        when(ratings.findBySessionId(2L)).thenReturn(Optional.empty());

        List<ConsultSessionMetaRow> rows = service.search(42L, null, null, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).sessionId()).isEqualTo(1L);
        assertThat(rows.get(0).stars()).isEqualTo(5);
        assertThat(rows.get(0).comment()).isEqualTo("很好");
        assertThat(rows.get(0).status()).isEqualTo("CLOSED");
        assertThat(rows.get(1).userId()).isNull(); // 容空
        assertThat(rows.get(1).stars()).isNull();
        assertThat(rows.get(1).comment()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyResultWhenNoMatch() {
        when(sessions.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        assertThat(service.search(null, 999L, null, null)).isEmpty();
    }
}

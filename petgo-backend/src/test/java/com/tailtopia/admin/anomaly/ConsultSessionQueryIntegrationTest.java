package com.tailtopia.admin.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.dto.ConsultSessionMetaRow;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultSessionAdminQueryService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：问诊会话元数据查询（Story 5.2，需 Docker postgres+redis）。验 Specification 动态多维查询经真 PG、
 * 评分 join、空 userId 容错、日期范围。只读、无新表。
 */
class ConsultSessionQueryIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultSessionAdminQueryService queryService;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private ConsultRatingRepository ratings;

    @Test
    void queriesByUserWithRatingJoin() {
        long userId = 9_700_000L + SEQ.incrementAndGet();
        ConsultSession s1 = sessions.save(ConsultSession.startWaiting(userId, ConsultSource.DIRECT));
        sessions.save(ConsultSession.startWaiting(userId, ConsultSource.DIRECT));
        ratings.save(ConsultRating.of(s1.getId(), 200L, userId, 5, "很专业"));

        List<ConsultSessionMetaRow> rows = queryService.search(userId, null, null, null);

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.userId() != null && r.userId() == userId);
        ConsultSessionMetaRow rated = rows.stream()
                .filter(r -> r.sessionId() == s1.getId()).findFirst().orElseThrow();
        assertThat(rated.stars()).isEqualTo(5);
        assertThat(rated.comment()).isEqualTo("很专业");
    }

    @Test
    void dateRangeExcludesOutOfWindow() {
        long userId = 9_800_000L + SEQ.incrementAndGet();
        sessions.save(ConsultSession.startWaiting(userId, ConsultSource.DIRECT));

        // 起点设在未来 → 当前会话不在窗内。
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        assertThat(queryService.search(userId, null, future, null)).isEmpty();
        // 不限时间 → 命中。
        assertThat(queryService.search(userId, null, null, null)).hasSize(1);
    }
}

package com.tailtopia.admin.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.risk.domain.RedOverageReview;
import com.tailtopia.admin.risk.dto.RedOverageRow;
import com.tailtopia.admin.risk.repository.RedOverageReviewRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.repository.RedCountProjection;
import com.tailtopia.triage.repository.TriageTaskRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 9.6，AB-7A）：RED 计数聚合 join 复核态 + 标记校验/审计（纯注记，无自动处置）。 */
class RedOverageMonitorServiceTest {

    private TriageTaskRepository triage;
    private RedOverageReviewRepository reviews;
    private AdminAuditService audit;
    private RedOverageMonitorService svc;

    @BeforeEach
    void setUp() {
        triage = Mockito.mock(TriageTaskRepository.class);
        reviews = Mockito.mock(RedOverageReviewRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new RedOverageMonitorService(triage, reviews, audit);
    }

    private RedCountProjection count(long userId, long redCount) {
        return new RedCountProjection() {
            @Override
            public long getUserId() {
                return userId;
            }

            @Override
            public long getRedCount() {
                return redCount;
            }
        };
    }

    @Test
    void listJoinsCountsWithReviewStatus() {
        when(triage.redCountsByUser()).thenReturn(List.of(count(100L, 5), count(200L, 2)));
        RedOverageReview r = RedOverageReview.of(100L, RedOverageReview.TO_VERIFY, "看看", 7L);
        when(reviews.findByUserIdIn(List.of(100L, 200L))).thenReturn(List.of(r));

        List<RedOverageRow> rows = svc.list();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).userId()).isEqualTo(100L);
        assertThat(rows.get(0).redCount()).isEqualTo(5);
        assertThat(rows.get(0).reviewStatus()).isEqualTo("TO_VERIFY");
        assertThat(rows.get(1).userId()).isEqualTo(200L);
        assertThat(rows.get(1).reviewStatus()).isEmpty(); // 未标记
    }

    @Test
    void markRejectsInvalidStatus() {
        assertThatThrownBy(() -> svc.mark(100L, "BOGUS", null, 7L)).isInstanceOf(AppException.class);
        verify(reviews, never()).save(Mockito.any());
    }

    @Test
    void markPersistsAndAudits() {
        when(reviews.findById(100L)).thenReturn(Optional.empty());

        svc.mark(100L, RedOverageReview.RESOLVED, "已处理", 7L);

        verify(reviews).save(Mockito.any(RedOverageReview.class));
        verify(audit).record(eq(7L), eq("RED_OVERAGE_REVIEW"), anyString(), eq("100"), anyString());
    }
}

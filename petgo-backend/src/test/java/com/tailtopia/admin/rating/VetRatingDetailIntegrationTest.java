package com.tailtopia.admin.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.service.AdminVetService;
import com.tailtopia.consult.domain.ConsultRating;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.InterruptReason;
import com.tailtopia.consult.dto.UnratedReason;
import com.tailtopia.consult.dto.VetUnratedConsult;
import com.tailtopia.consult.repository.ConsultRatingRepository;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import com.tailtopia.vet.domain.VetAccount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：单兽医评分详情·未评列表（Story 6.2，需 Docker postgres+redis）。造 RATED（有评分行，排除）+
 * UNRATED（CLOSED 无评分→超时）+ INTERRUPTED（中断）各一，验未评列表与原因映射经真 PG。
 */
class VetRatingDetailIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminVetService adminVetService;
    @Autowired
    private VetTestSupport vetSupport;
    @Autowired
    private ConsultRatingRepository ratings;
    @Autowired
    private ConsultSessionRepository sessions;

    private ConsultSession newInterruptedSession(long userId, long vetId) {
        ConsultSession s = ConsultSession.startWaiting(userId, ConsultSource.DIRECT);
        s.markInProgress(vetId);
        s.interrupt(InterruptReason.VET_BANNED);
        return sessions.save(s);
    }

    @Test
    void unratedConsultsExcludesRatedAndMapsReasons() {
        VetAccount vet = vetSupport.newActiveVet("未评明细兽医-" + SEQ.incrementAndGet());
        long vetId = vet.getId();
        long u1 = 9_950_000L + SEQ.incrementAndGet();
        long u2 = 9_950_000L + SEQ.incrementAndGet();
        long u3 = 9_950_000L + SEQ.incrementAndGet();

        ConsultSession rated = vetSupport.newClosedSession(u1, vetId);
        ratings.save(ConsultRating.of(rated.getId(), vetId, u1, 5, "好")); // 已评 → 排除
        ConsultSession timeout = vetSupport.newClosedSession(u2, vetId);    // CLOSED 无评分 → 超时未评
        ConsultSession interrupted = newInterruptedSession(u3, vetId); // 中断未评

        List<VetUnratedConsult> unrated = adminVetService.unratedConsults(vetId);

        assertThat(unrated).extracting(VetUnratedConsult::sessionId)
                .contains(timeout.getId(), interrupted.getId())
                .doesNotContain(rated.getId());
        assertThat(unrated.stream().filter(u -> u.sessionId() == timeout.getId()).findFirst()
                .orElseThrow().reason()).isEqualTo(UnratedReason.TIMEOUT_UNRATED);
        assertThat(unrated.stream().filter(u -> u.sessionId() == interrupted.getId()).findFirst()
                .orElseThrow().reason()).isEqualTo(UnratedReason.INTERRUPTED);
    }
}

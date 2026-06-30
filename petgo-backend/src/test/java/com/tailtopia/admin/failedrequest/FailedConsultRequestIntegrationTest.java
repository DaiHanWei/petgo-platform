package com.tailtopia.admin.failedrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.failedrequest.domain.CancelReason;
import com.tailtopia.admin.failedrequest.domain.FailedConsultRequest;
import com.tailtopia.admin.failedrequest.repository.FailedConsultRequestRepository;
import com.tailtopia.admin.failedrequest.service.FailedConsultRequestService;
import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultSessionService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1：失败请求端到端（Story 2.9，需 Docker postgres+redis）。V35 validate 绿；用户取消 → 事件 → 落库（含在线兽医数）；
 * SYSTEM_FAILURE 未跟进禁归档、跟进后可归档。
 */
class FailedConsultRequestIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultSessionService consultSessionService;
    @Autowired
    private ConsultSessionRepository sessions;
    @Autowired
    private FailedConsultRequestRepository failedRepo;
    @Autowired
    private FailedConsultRequestService failedService;

    @Test
    void userCancelRecordsFailedRequest() {
        long seq = SEQ.incrementAndGet();
        long userId = 70000L + seq;
        ConsultSession s = sessions.save(ConsultSession.startWaiting(userId, ConsultSource.DIRECT));

        long before = failedRepo.findByArchivedAtIsNullOrderByCancelledAtDesc().size();
        consultSessionService.cancel(userId, s.getId());

        List<FailedConsultRequest> active = failedRepo.findByArchivedAtIsNullOrderByCancelledAtDesc();
        assertThat(active.size()).isGreaterThan((int) before);
        FailedConsultRequest mine = active.stream()
                .filter(r -> r.getUserId().equals(userId)).findFirst().orElseThrow();
        assertThat(mine.getCancelReason()).isEqualTo(CancelReason.USER_CANCEL);
        assertThat(mine.getRequestToken()).isNotBlank();
    }

    @Test
    void systemFailureRequiresFollowUpBeforeArchive() {
        long seq = SEQ.incrementAndGet();
        FailedConsultRequest r = failedRepo.save(FailedConsultRequest.of(
                "tok-sf-" + seq, 71000L + seq, null,
                java.time.Instant.now(), java.time.Instant.now(), CancelReason.SYSTEM_FAILURE, 0));
        long actor = 720000L + seq;

        // 未跟进禁归档。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failedService.archive(r.getId(), actor))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);

        // 跟进后可归档。
        failedService.followUp(r.getId(), actor);
        failedService.archive(r.getId(), actor);
        assertThat(failedRepo.findById(r.getId()).orElseThrow().isArchived()).isTrue();
    }
}

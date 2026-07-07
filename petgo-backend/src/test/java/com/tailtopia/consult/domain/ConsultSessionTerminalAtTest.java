package com.tailtopia.consult.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Bug 20260706-264：兽医结束后问诊历史时间必须冻结在结束那一刻，之后 {@code updatedAt} 被
 * bump（30min 窗口到期 closeUnrated / 补评分等）不得改变 {@link ConsultSession#terminalAt()}。
 */
class ConsultSessionTerminalAtTest {

    private static ConsultSession activeSession(Instant created) {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "createdAt", created);
        ReflectionTestUtils.setField(s, "updatedAt", created);
        s.markInProgress(99L);
        return s;
    }

    @Test
    void terminalAt_frozenAtVetEnd_notAffectedByLaterUpdatedAtBump() {
        Instant t0 = Instant.parse("2026-07-06T10:00:00Z");
        ConsultSession s = activeSession(t0);

        s.endByVet(); // PENDING_CLOSE：pendingCloseStartedAt 定格
        Instant endMoment = (Instant) ReflectionTestUtils.getField(s, "pendingCloseStartedAt");
        assertThat(s.terminalAt()).isEqualTo(endMoment);

        // 模拟 30min 窗口到期关闭 + 后续 touch 把 updatedAt 推到很晚。
        s.closeUnrated();
        ReflectionTestUtils.setField(s, "updatedAt", t0.plusSeconds(3600));

        // 结束时刻仍冻结，不随 updatedAt 漂移。
        assertThat(s.terminalAt()).isEqualTo(endMoment);
        assertThat(s.terminalAt()).isNotEqualTo(t0.plusSeconds(3600));
    }

    @Test
    void terminalAt_interrupted_usesInterruptedAt() {
        Instant t0 = Instant.parse("2026-07-06T10:00:00Z");
        ConsultSession s = activeSession(t0);

        s.interrupt(InterruptReason.VET_BANNED);
        Instant interruptedAt = (Instant) ReflectionTestUtils.getField(s, "interruptedAt");
        ReflectionTestUtils.setField(s, "updatedAt", t0.plusSeconds(3600));

        assertThat(s.terminalAt()).isEqualTo(interruptedAt);
    }
}

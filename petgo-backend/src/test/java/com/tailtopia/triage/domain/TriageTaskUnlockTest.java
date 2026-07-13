package com.tailtopia.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L0 —— Story 2.2 {@link TriageTask} 解锁态：markDone 置 LOCKED、失败恒 null、{@code unlock()} 一次性跃迁
 * 与不可覆盖/校验。
 */
class TriageTaskUnlockTest {

    private static TriageTask fresh() {
        return TriageTask.submit(7L, null, "咳嗽", null, "idem", "en");
    }

    private static TriageTask doneTask() {
        TriageTask t = fresh();
        t.markProcessing();
        t.markDone(DangerLevel.YELLOW, Map.of(), Map.of("advice", "观察"));
        return t;
    }

    @Test
    void markDoneInitializesUnlockSourceToLocked() {
        TriageTask t = doneTask();
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.LOCKED);
        assertThat(t.getUnlockChannel()).isNull();
    }

    @Test
    void pendingAndFailedHaveNullUnlockSource() {
        assertThat(fresh().getUnlockSource()).isNull(); // PENDING
        TriageTask failed = fresh();
        failed.markProcessing();
        failed.markFailed(); // 失败不经 markDone → 恒 null（生成失败不建记录）
        assertThat(failed.getUnlockSource()).isNull();
    }

    @Test
    void unlockFreeQuotaTransitionsFromLocked() {
        TriageTask t = doneTask();
        t.unlock(UnlockSource.FREE_QUOTA, null);
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.FREE_QUOTA);
        assertThat(t.getUnlockChannel()).isNull();
    }

    @Test
    void unlockPaidTransitionsWithChannel() {
        TriageTask t = doneTask();
        t.unlock(UnlockSource.PAID, UnlockChannel.QRIS);
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.PAID);
        assertThat(t.getUnlockChannel()).isEqualTo(UnlockChannel.QRIS);
    }

    @Test
    void unlockOnceWrittenCannotBeOverwritten() {
        TriageTask t = doneTask();
        t.unlock(UnlockSource.FREE_QUOTA, null);
        // 已解锁再 unlock → 幂等拒绝（不可覆盖）。
        assertThatIllegalStateException()
                .isThrownBy(() -> t.unlock(UnlockSource.PAID, UnlockChannel.DANA));
        assertThat(t.getUnlockSource()).isEqualTo(UnlockSource.FREE_QUOTA); // 未被覆盖
    }

    @Test
    void paidWithoutChannelRejected() {
        TriageTask t = doneTask();
        assertThatIllegalArgumentException().isThrownBy(() -> t.unlock(UnlockSource.PAID, null));
    }

    @Test
    void freeQuotaWithChannelRejected() {
        TriageTask t = doneTask();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> t.unlock(UnlockSource.FREE_QUOTA, UnlockChannel.QRIS));
    }

    @Test
    void unlockTargetMustBeFreeQuotaOrPaid() {
        TriageTask t = doneTask();
        assertThatIllegalArgumentException().isThrownBy(() -> t.unlock(UnlockSource.LOCKED, null));
    }
}

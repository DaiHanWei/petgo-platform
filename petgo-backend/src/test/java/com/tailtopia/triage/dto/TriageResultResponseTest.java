package com.tailtopia.triage.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.triage.TriageTestSupport;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.domain.UnlockSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L0 —— Story 2.2 分字段下发与红色永不锁（{@link TriageResultResponse#from}）。
 *
 * <p><b>头等断言</b>：{@code RED + LOCKED}（甚至 unlock_source=null/额度耗尽语义）→ 详建 advice 仍下发、
 * {@code locked=false}（FR-43C 红色永不锁，响应层单点）。非红锁定态 → 详建置 null、{@code locked=true}，
 * 但安全免费部分（disclaimer/observation/emergency*）恒下发。
 */
class TriageResultResponseTest {

    private static final Map<String, Object> PARSED = Map.of(
            "advice", "多喝水观察",
            "medicationRef", "参考用药 X",
            "disclaimer", "仅供参考，不替代兽医",
            "observation", Map.of(
                    "indicators", List.of("精神", "食欲"),
                    "timeWindow", "24 小时",
                    "escalationTriggers", List.of("持续呕吐")),
            "emergencySteps", List.of("移到阴凉处"),
            "emergencyAvoid", List.of("勿强行喂食"));

    private static TriageTask done(DangerLevel level, UnlockSource unlockSource) {
        TriageTask t = TriageTestSupport.task(1L, 7L, TriageStatus.DONE, "x", null);
        TriageTestSupport.set(t, "dangerLevel", level);
        TriageTestSupport.set(t, "parsedResult", PARSED);
        TriageTestSupport.set(t, "unlockSource", unlockSource);
        return t;
    }

    private static void assertSafetyFieldsAlwaysPresent(TriageResultResponse r) {
        assertThat(r.disclaimer()).isEqualTo("仅供参考，不替代兽医");
        assertThat(r.observation()).isNotNull();
        assertThat(r.observation().timeWindow()).isEqualTo("24 小时");
        assertThat(r.emergencySteps()).containsExactly("移到阴凉处");
        assertThat(r.emergencyAvoid()).containsExactly("勿强行喂食");
    }

    // ---- AC5 头等：红色永不锁 ----

    @Test
    void redLockedStillDeliversDetailedAdvice() {
        // RED + unlock_source=LOCKED（未解锁、额度耗尽语义）→ 详建仍下发、locked=false。
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.RED, UnlockSource.LOCKED));
        assertThat(r.advice()).isEqualTo("多喝水观察");
        assertThat(r.medicationRef()).isEqualTo("参考用药 X");
        assertThat(r.locked()).isFalse();
        assertSafetyFieldsAlwaysPresent(r);
    }

    @Test
    void redWithNullUnlockSourceStillDeliversDetailedAdvice() {
        // RED + unlock_source=null（历史/未置）→ 仍红色永不锁。
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.RED, null));
        assertThat(r.advice()).isEqualTo("多喝水观察");
        assertThat(r.locked()).isFalse();
    }

    // ---- AC4：非红锁定态 ----

    @Test
    void yellowLockedHidesDetailButKeepsSafetyFields() {
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.YELLOW, UnlockSource.LOCKED));
        assertThat(r.advice()).isNull();
        assertThat(r.medicationRef()).isNull();
        assertThat(r.locked()).isTrue();
        assertThat(r.dangerLevel()).isEqualTo(DangerLevel.YELLOW); // 等级/颜色始终免费
        assertSafetyFieldsAlwaysPresent(r);
    }

    @Test
    void greenLockedHidesDetail() {
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.GREEN, UnlockSource.LOCKED));
        assertThat(r.advice()).isNull();
        assertThat(r.locked()).isTrue();
    }

    @Test
    void yellowNullUnlockSourceTreatedAsLocked() {
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.YELLOW, null));
        assertThat(r.advice()).isNull();
        assertThat(r.locked()).isTrue();
    }

    // ---- AC4：解锁态 ----

    @Test
    void yellowFreeQuotaUnlocksDetail() {
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.YELLOW, UnlockSource.FREE_QUOTA));
        assertThat(r.advice()).isEqualTo("多喝水观察");
        assertThat(r.medicationRef()).isEqualTo("参考用药 X");
        assertThat(r.locked()).isFalse();
    }

    @Test
    void greenPaidUnlocksDetail() {
        TriageResultResponse r = TriageResultResponse.from(done(DangerLevel.GREEN, UnlockSource.PAID));
        assertThat(r.advice()).isEqualTo("多喝水观察");
        assertThat(r.locked()).isFalse();
    }

    // ---- 非 DONE：仅回 status ----

    @Test
    void notDoneReturnsStatusOnly() {
        TriageTask proc = TriageTestSupport.task(1L, 7L, TriageStatus.PROCESSING, "x", null);
        TriageResultResponse r = TriageResultResponse.from(proc);
        assertThat(r.status()).isEqualTo(TriageStatus.PROCESSING);
        assertThat(r.dangerLevel()).isNull();
        assertThat(r.advice()).isNull();
        assertThat(r.locked()).isNull();
        assertThat(r.unlockSource()).isNull();
    }
}

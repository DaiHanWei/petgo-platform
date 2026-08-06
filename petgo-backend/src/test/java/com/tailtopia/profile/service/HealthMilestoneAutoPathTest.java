package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.HealthMilestones;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Story 5.1 · L0：健康类里程碑的**自动达成路径**（FR-86）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>健康记录类型 → 里程碑映射（本 Story 补 {@code NEUTER → M9}；月经/自定义刻意不映射）；</li>
 *   <li>**真人兽医咨询结束 → M5**；AI 分诊不发 {@code ConsultClosedEvent}，故天然不解锁（OQ-17）；</li>
 *   <li>「健康类里程碑集合」的单一定义（{@link HealthMilestones}）—— 5.2 护栏与 6.1 埋点都引用它。</li>
 * </ol>
 */
class HealthMilestoneAutoPathTest {

    private MilestoneCompletionService completion;
    private MilestoneAutoCompleteListener listener;

    @BeforeEach
    void setUp() {
        completion = Mockito.mock(MilestoneCompletionService.class);
        listener = new MilestoneAutoCompleteListener(completion);
    }

    // ===== AC1 类型映射 =====

    @Test
    void neuterRecordCompletesM9() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.NEUTER));

        verify(completion).completeForOwner(7L, "M9", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void vaccineAndDewormKeepExistingMapping() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.VACCINE));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.DEWORM));

        verify(completion).completeForOwner(7L, "M3", MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion).completeForOwner(7L, "M4", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void menstruationAndCustomMapToNothing() {
        listener.onHealthRecordCreated(
                new HealthRecordCreatedEvent(7L, HealthRecordType.MENSTRUATION));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.CUSTOM));

        // PRD 明确：这两类无对应里程碑节点 —— 只应触发「Lulus Pemula」聚合尝试，不完成任何里程碑。
        verify(completion, never()).completeForOwner(anyLong(), eq("M3"), Mockito.any());
        verify(completion, never()).completeForOwner(anyLong(), eq("M4"), Mockito.any());
        verify(completion, never()).completeForOwner(anyLong(), eq("M9"), Mockito.any());
        verify(completion, never()).completeForOwner(anyLong(), eq("M5"), Mockito.any());
    }

    // ===== AC2 M5：真人兽医咨询结束 =====

    @Test
    void vetConsultClosedCompletesM5() {
        listener.onConsultClosed(new ConsultClosedEvent(1L, 7L, 42L, 9L, "im-1", List.of(), true,
                LocalDate.of(2026, 8, 4), "摘要", "GREEN", "建议"));

        verify(completion).completeForOwner(7L, "M5", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void aiTriageCannotCompleteM5_becauseItNeverPublishesThisEvent() {
        // OQ-17 的落地方式是**模块隔离**而非条件判断：AI 分诊在 triage 模块、不发 ConsultClosedEvent。
        // 因此本监听器上没有、也不需要「排除 AI」的分支 —— 这条断言把该设计钉住：
        // 监听方法只接受 ConsultClosedEvent 这一种入参类型。
        List<Method> subscribers = List.of(MilestoneAutoCompleteListener.class.getDeclaredMethods())
                .stream()
                .filter(m -> m.getName().equals("onConsultClosed"))
                .toList();
        assertThat(subscribers).hasSize(1);
        assertThat(subscribers.get(0).getParameterTypes()).containsExactly(ConsultClosedEvent.class);
    }

    @Test
    void m5AndS4AreSeparateSubscriptions_notMerged() {
        // S4「第一次保存兽医问诊结论」订 HealthArchivedEvent（用户可跳过存档）；
        // M5「第一次看兽医」订 ConsultClosedEvent（看过就算）。合并会让「跳过存档」把 M5 一起吃掉。
        var names = List.of(MilestoneAutoCompleteListener.class.getDeclaredMethods()).stream()
                .map(Method::getName).toList();
        assertThat(names).contains("onConsultClosed", "onHealthArchived");
    }

    // ===== B0 健康类里程碑集合的单一定义 =====

    @Test
    void healthMilestoneSetIsTheFourAutoOnlyOnes_acrossAllSeries() {
        assertThat(HealthMilestones.SUFFIXES).containsExactlyInAnyOrder("M3", "M4", "M5", "M9");
        for (String prefix : List.of("C", "D", "G")) {
            for (String suffix : HealthMilestones.SUFFIXES) {
                assertThat(HealthMilestones.isHealthMilestone(prefix + "-" + suffix)).isTrue();
            }
            // 非健康类：打卡路径保留，不受 5.2 护栏影响
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-S1")).isFalse();
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-L2")).isFalse();
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-S4")).isFalse();
        }
        assertThat(HealthMilestones.isHealthMilestone(null)).isFalse();
    }
}

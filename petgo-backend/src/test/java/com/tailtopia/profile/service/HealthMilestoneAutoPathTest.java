package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tailtopia.consult.event.ConsultClosedEvent;
import com.tailtopia.profile.domain.HealthMilestones;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
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

        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_NEUTER,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void vaccineAndDewormKeepExistingMapping() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.VACCINE));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.DEWORM));

        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_VACCINE,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_DEWORM,
                MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void menstruationAndCustomMapToNothing() {
        listener.onHealthRecordCreated(
                new HealthRecordCreatedEvent(7L, HealthRecordType.MENSTRUATION));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.CUSTOM));

        // PRD 明确：这两类无对应里程碑节点 —— 只应触发「Lulus Pemula」聚合尝试，不完成任何里程碑。
        verify(completion, never()).completeForOwner(
                anyLong(), eq(MilestoneAutoEvent.HEALTH_RECORD_VACCINE), Mockito.any());
        verify(completion, never()).completeForOwner(
                anyLong(), eq(MilestoneAutoEvent.HEALTH_RECORD_DEWORM), Mockito.any());
        verify(completion, never()).completeForOwner(
                anyLong(), eq(MilestoneAutoEvent.HEALTH_RECORD_NEUTER), Mockito.any());
        verify(completion, never()).completeForOwner(
                anyLong(), eq(MilestoneAutoEvent.CONSULT_CLOSED), Mockito.any());
    }

    // ===== AC2 M5：真人兽医咨询结束 =====

    @Test
    void vetConsultClosedCompletesVetVisitMilestone() {
        listener.onConsultClosed(new ConsultClosedEvent(1L, 7L, 42L, 9L, "im-1", List.of(), true,
                LocalDate.of(2026, 8, 4), "摘要", "GREEN", "建议"));

        verify(completion).completeForOwner(7L, MilestoneAutoEvent.CONSULT_CLOSED,
                MilestoneCompletionSource.SYSTEM_AUTO);
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

    /**
     * V1.3.0 Story 1.2：集合改按完整 code，并补进通用宠物的 G-M1 / G-M2。
     *
     * <p>三系成员数不等是正常的（猫狗各 4、通用 2）：通用清单本就没有独立的驱虫 / 绝育节点。
     * **别为了"对齐"给通用宠物硬凑两条。**
     */
    @Test
    void healthMilestoneSetIsListedByFullCode() {
        assertThat(HealthMilestones.CODES).containsExactlyInAnyOrder(
                "C-M3", "C-M4", "C-M5", "C-M9",
                "D-M3", "D-M4", "D-M5", "D-M9",
                "G-M1", "G-M2");
        for (String code : HealthMilestones.CODES) {
            assertThat(HealthMilestones.isHealthMilestone(code)).isTrue();
        }
        for (String prefix : List.of("C", "D", "G")) {
            // 非健康类：打卡路径保留，不受 5.2 护栏影响
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-S1")).isFalse();
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-L2")).isFalse();
            assertThat(HealthMilestones.isHealthMilestone(prefix + "-S4")).isFalse();
        }
        // 🔴 通用清单的 M3 / M4 是「陪伴满 30 天」「记录满 10 条」，与健康无关；
        //    M5 / M9 在通用清单压根不存在。按后缀判会把这四个都误判成健康类。
        assertThat(HealthMilestones.isHealthMilestone("G-M3")).isFalse();
        assertThat(HealthMilestones.isHealthMilestone("G-M4")).isFalse();
        assertThat(HealthMilestones.isHealthMilestone("G-M5")).isFalse();
        assertThat(HealthMilestones.isHealthMilestone("G-M9")).isFalse();
        assertThat(HealthMilestones.isHealthMilestone(null)).isFalse();
    }
}

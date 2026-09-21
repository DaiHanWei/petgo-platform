package com.tailtopia.profile.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneAutoEvent;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import org.junit.jupiter.api.Test;

/**
 * L0：里程碑第四触发路径映射（Story 7.2 · AC2，FR-45C）——VACCINE / DEWORM / NEUTER 各自映射到一个自动事件，其它类型不映射。
 * V1.3.0 Story 1.1 起断言落在**事件**上，「事件 → 哪个物种点亮哪条 code」由
 * {@code MilestoneAutoCompleteMapTest} 的映射矩阵钉住。
 * Story 7.3 起：**任一 type** 创建健康记录都追加 Lulus Pemula 聚合解锁尝试（第 6 新手任务）。
 * 端到端 @Async+AFTER_COMMIT 属框架标准（同 S1/S4 生产已验），不在此重测。
 */
class MilestoneHealthRecordPathTest {

    private final MilestoneCompletionService completion = mock(MilestoneCompletionService.class);
    private final MilestoneAutoCompleteListener listener = new MilestoneAutoCompleteListener(completion);

    @Test
    void vaccineCompletesM3AndTriesLulusPemula() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.VACCINE));
        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_VACCINE,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion).maybeUnlockLulusPemulaForOwner(7L); // 第 6 新手任务
    }

    @Test
    void dewormCompletesM4AndTriesLulusPemula() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.DEWORM));
        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_DEWORM,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion).maybeUnlockLulusPemulaForOwner(7L);
    }

    /**
     * ⚠️ Story 5.1（FR-86）起 **NEUTER 已映射到 M9**，不再属于「不映射任何里程碑」的一档 ——
     * 原断言把它和月经/自定义一起断言「不完成任何里程碑」，已到期。
     * 现在只有**月经 / 自定义**刻意不映射（PRD：无对应节点）。
     */
    @Test
    void menstruationAndCustomSkipMilestonesButStillTryLulusPemula() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.MENSTRUATION));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.CUSTOM));
        // 无任何里程碑完成……
        verify(completion, never()).completeForOwner(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(MilestoneAutoEvent.class),
                org.mockito.ArgumentMatchers.any());
        // ……但每次都尝试 Lulus Pemula 聚合解锁（健康记录=第 6 任务）。
        verify(completion, times(2)).maybeUnlockLulusPemulaForOwner(7L);
    }

    /** Story 5.1 新增映射：录绝育 → M9（2026-07-29 产品确认）。 */
    @Test
    void neuterCompletesM9AndTriesLulusPemula() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.NEUTER));
        verify(completion).completeForOwner(7L, MilestoneAutoEvent.HEALTH_RECORD_NEUTER,
                MilestoneCompletionSource.SYSTEM_AUTO);
        verify(completion).maybeUnlockLulusPemulaForOwner(7L);
    }
}

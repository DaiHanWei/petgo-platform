package com.tailtopia.profile.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.event.HealthRecordCreatedEvent;
import org.junit.jupiter.api.Test;

/**
 * L0：里程碑第四触发路径映射（Story 7.2 · AC2，FR-45C）——VACCINE→M3 / DEWORM→M4 / 其它不触发。
 * 端到端 @Async+AFTER_COMMIT 属框架标准（同 S1/S4 生产已验），不在此重测。
 */
class MilestoneHealthRecordPathTest {

    private final MilestoneCompletionService completion = mock(MilestoneCompletionService.class);
    private final MilestoneAutoCompleteListener listener = new MilestoneAutoCompleteListener(completion);

    @Test
    void vaccineCompletesM3() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.VACCINE));
        verify(completion).completeForOwner(7L, "M3", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void dewormCompletesM4() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.DEWORM));
        verify(completion).completeForOwner(7L, "M4", MilestoneCompletionSource.SYSTEM_AUTO);
    }

    @Test
    void otherTypesDoNotTrigger() {
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.MENSTRUATION));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.NEUTER));
        listener.onHealthRecordCreated(new HealthRecordCreatedEvent(7L, HealthRecordType.CUSTOM));
        verifyNoInteractions(completion);
    }
}

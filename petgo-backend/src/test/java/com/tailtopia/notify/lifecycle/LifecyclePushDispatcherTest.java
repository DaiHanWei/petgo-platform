package com.tailtopia.notify.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.LifecyclePushMark;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.LifecyclePushMarkRepository;
import com.tailtopia.notify.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：生命周期推送投递（V1.5.0 onboarding-recall Story 6.1 · AC5 / AC7 / AC10）—— mark variant 存 {@code <层>_<变体>}、
 * {@code SKIPPED_ACTIVE} 只写 mark 不发通知、走 {@code sendRendered(push=true)} 且参数为 {宠物名, 品种词, N}、
 * targetRef = 变体名、已推跳过。
 */
class LifecyclePushDispatcherTest {

    private final LifecyclePushMarkRepository marks = mock(LifecyclePushMarkRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final LifecyclePushDispatcher dispatcher = new LifecyclePushDispatcher(marks, notifications);

    private static LifecyclePlannedPush push(NotificationType type, LifecycleVariant v, LifecycleLayer layer,
            String copy, String petName, String breed, String petType, int remaining) {
        return new LifecyclePlannedPush(42L, type, LifecyclePlannedPush.ONCE, v, petName, layer, copy, breed, petType,
                remaining);
    }

    private String savedMarkVariant() {
        ArgumentCaptor<LifecyclePushMark> m = ArgumentCaptor.forClass(LifecyclePushMark.class);
        verify(marks).save(m.capture());
        return (String) org.springframework.test.util.ReflectionTestUtils.getField(m.getValue(), "variant");
    }

    @Test
    @DisplayName("D 层 D1 SQUAD：mark=D_DISCOVER；sendRendered(copyKey=LIFECYCLE_D1.SQUAD, args=[Mochi, Persia, 0], targetRef=DISCOVER, push=true)")
    void squadUsesSendRendered() {
        dispatcher.dispatch(push(NotificationType.LIFECYCLE_D1, LifecycleVariant.DISCOVER, LifecycleLayer.D, "SQUAD",
                "Mochi", "Persia", "CAT", 0));

        assertThat(savedMarkVariant()).isEqualTo("D_DISCOVER");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).sendRendered(eq(42L), eq(NotificationType.LIFECYCLE_D1), eq("LIFECYCLE_D1.SQUAD"),
                args.capture(), anyString(), anyString(), eq("DISCOVER"), eq(true));
        assertThat(args.getValue()).containsExactly("Mochi", "Persia", "0");
        verify(notifications, never()).sendWithCopy(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("AC5：SKIPPED_ACTIVE → 只写 mark（C_SKIPPED_ACTIVE），不写通知不推")
    void skippedActiveOnlyWritesMark() {
        dispatcher.dispatch(push(NotificationType.LIFECYCLE_D1, LifecycleVariant.SKIPPED_ACTIVE, LifecycleLayer.C,
                "RECORD", "Mochi", null, "CAT", 0));
        assertThat(savedMarkVariant()).isEqualTo("C_SKIPPED_ACTIVE");
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("D7 还差 N 项：args {2}=N；A 层无宠物名 → 兜底 hewanmu、品种词回退物种 OTHER")
    void tasksLeftPassesRemaining() {
        dispatcher.dispatch(push(NotificationType.LIFECYCLE_D7, LifecycleVariant.NEWBIE_TASKS, LifecycleLayer.A,
                "TASKS_LEFT", null, null, null, 3));
        assertThat(savedMarkVariant()).isEqualTo("A_NEWBIE_TASKS");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> fbBody = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendRendered(eq(42L), eq(NotificationType.LIFECYCLE_D7), eq("LIFECYCLE_D7.TASKS_LEFT"),
                args.capture(), anyString(), fbBody.capture(), eq("NEWBIE_TASKS"), anyBoolean());
        assertThat(args.getValue()[0]).isEqualTo("hewanmu");
        assertThat(args.getValue()[2]).isEqualTo("3");
        assertThat(args.getValue()[1]).isInstanceOf(org.springframework.context.MessageSourceResolvable.class);
        assertThat(fbBody.getValue()).contains("Tinggal 3");
    }

    @Test
    @DisplayName("已有 mark → 跳过")
    void alreadyMarked() {
        when(marks.existsByUserIdAndPushKindAndNodeKey(42L, "LIFECYCLE_D7", "ONCE")).thenReturn(true);
        dispatcher.dispatch(push(NotificationType.LIFECYCLE_D7, LifecycleVariant.GRADUATED, LifecycleLayer.E,
                "GRADUATED", "Mochi", null, "CAT", 0));
        verify(marks, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("5 参兼容构造（召回）：mark 只写变体名、copyKey = type.变体")
    void legacyPlannedPush() {
        LifecyclePlannedPush p = new LifecyclePlannedPush(42L, NotificationType.LIFECYCLE_WINBACK, "2026-11",
                LifecycleVariant.RECORD, "Mochi");
        assertThat(p.copyKey()).isEqualTo("LIFECYCLE_WINBACK.RECORD");
        assertThat(p.markVariant()).isEqualTo("RECORD");
    }
}

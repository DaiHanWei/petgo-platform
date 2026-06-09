package com.petgo.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.domain.InterruptReason;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.event.ConsultInterruptedEvent;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.im.TencentImClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试：封禁批量中断进行中会话 → INTERRUPTED + IM 系统消息 + 中断事件（不发存档事件）。
 */
@ExtendWith(MockitoExtension.class)
class ConsultInterruptServiceTest {

    @Mock
    ConsultSessionRepository sessions;
    @Mock
    TencentImClient imClient;
    @Mock
    ApplicationEventPublisher events;

    private ConsultInterruptService service() {
        return new ConsultInterruptService(sessions, imClient, events);
    }

    private ConsultSession inProgress(long id, long vetId) {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "id", id);
        s.markInProgress(vetId);
        s.attachImConversation("conv-" + id);
        return s;
    }

    @Test
    void interruptByVetBanMovesSessionsToInterruptedWithSystemMessageAndEvent() {
        ConsultSession s = inProgress(11L, 3L);
        when(sessions.findByVetIdAndStatusIn(eq3(), any())).thenReturn(List.of(s));

        int count = service().interruptByVetBan(3L);

        assertThat(count).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.INTERRUPTED);
        assertThat(s.getInterruptedReason()).isEqualTo(InterruptReason.VET_BANNED);
        assertThat(s.getInterruptedAt()).isNotNull();
        verify(imClient).sendSystemMessage(org.mockito.ArgumentMatchers.eq("conv-11"), anyString());
        verify(events).publishEvent(any(ConsultInterruptedEvent.class));
    }

    @Test
    void interruptByVetBanWithNoActiveSessionsIsNoop() {
        when(sessions.findByVetIdAndStatusIn(eq3(), any())).thenReturn(List.of());
        assertThat(service().interruptByVetBan(3L)).isZero();
    }

    /**
     * AC3（F12 语义对齐）：封禁中断的查询范围**仅** {@code IN_PROGRESS / PENDING_CLOSE} 且**限本兽医**——
     * 锁定不误伤：① 未接的 WAITING 请求（vet_id 为空、属用户）不在范围；② 用户 kill 断线的会话留在
     * IN_PROGRESS 保护窗口由 5.6 处理，封禁中断只按 vetId+这两态命中本兽医已接会话，不触碰他人会话。
     */
    @Test
    void interruptScopesToBannedVetInProgressAndPendingCloseOnly() {
        when(sessions.findByVetIdAndStatusIn(eq3(), any())).thenReturn(List.of());

        service().interruptByVetBan(3L);

        @SuppressWarnings("unchecked")
        var statusCaptor = (org.mockito.ArgumentCaptor<java.util.Collection<SessionStatus>>)
                (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(sessions).findByVetIdAndStatusIn(eq3(), statusCaptor.capture());
        assertThat(statusCaptor.getValue())
                .containsExactlyInAnyOrder(SessionStatus.IN_PROGRESS, SessionStatus.PENDING_CLOSE)
                .doesNotContain(SessionStatus.WAITING); // 未接 WAITING 不被封禁误中断
    }

    private static long eq3() {
        return org.mockito.ArgumentMatchers.eq(3L);
    }
}

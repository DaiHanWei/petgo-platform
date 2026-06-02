package com.petgo.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.domain.SessionStatus;
import com.petgo.consult.event.ConsultAcceptedEvent;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.im.TencentImClient;
import com.petgo.vet.service.VetPresenceService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试（无 DB/redis/IM）：接单迁移 + 建会话 + 出队 + BUSY + 事件 + 系统消息；并发抢单/已被接走。
 */
@ExtendWith(MockitoExtension.class)
class ConsultAcceptServiceTest {

    @Mock
    ConsultSessionRepository repo;
    @Mock
    ConsultQueueService queue;
    @Mock
    VetPresenceService presence;
    @Mock
    TencentImClient imClient;
    @Mock
    ApplicationEventPublisher events;

    private ConsultAcceptService service() {
        return new ConsultAcceptService(repo, queue, presence, imClient, events);
    }

    private ConsultSession waiting(long id) {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    void acceptTransitionsBuildsConversationDequeuesAndGoesBusy() {
        ConsultSession s = waiting(11L);
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.saveAndFlush(s)).thenReturn(s);
        when(imClient.createConversation(anyString(), anyString())).thenReturn("conv-1");
        when(repo.save(s)).thenReturn(s);

        ConsultSession result = service().accept(3L, 11L);

        assertThat(result.getStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(result.getVetId()).isEqualTo(3L);
        assertThat(result.getImConversationId()).isEqualTo("conv-1");
        verify(queue).dequeue(11L);
        verify(presence).goBusy(3L);
        verify(events).publishEvent(any(ConsultAcceptedEvent.class));
        verify(imClient).sendSystemMessage(eqConv(), anyString());
    }

    private static String eqConv() {
        return org.mockito.ArgumentMatchers.eq("conv-1");
    }

    @Test
    void acceptRejectsAlreadyTakenSession() {
        ConsultSession s = waiting(11L);
        s.markInProgress(99L); // 已被别人接走
        when(repo.findById(11L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service().accept(3L, 11L)).isInstanceOf(AppException.class);
        verify(imClient, never()).createConversation(anyString(), anyString());
        verify(queue, never()).dequeue(anyLong());
    }

    @Test
    void acceptLosesConcurrentRaceOnOptimisticLock() {
        ConsultSession s = waiting(11L);
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.saveAndFlush(s)).thenThrow(new ObjectOptimisticLockingFailureException(ConsultSession.class, 11L));

        assertThatThrownBy(() -> service().accept(3L, 11L)).isInstanceOf(AppException.class);
        verify(imClient, never()).createConversation(anyString(), anyString());
        verify(presence, never()).goBusy(anyLong());
    }
}

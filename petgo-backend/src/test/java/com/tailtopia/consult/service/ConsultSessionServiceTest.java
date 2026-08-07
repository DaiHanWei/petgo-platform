package com.tailtopia.consult.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.domain.SessionStatus;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.consult.service.ConsultSessionService.CreateResult;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.dto.TriageUpgradeContext;
import com.tailtopia.triage.service.TriageService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0 单元测试（无 DB/redis，mock repo+queue）：发起 WAITING 入队、同时仅 1 个、取消出队、超时不迁移状态、归属校验。
 */
@ExtendWith(MockitoExtension.class)
class ConsultSessionServiceTest {

    @Mock
    ConsultSessionRepository repo;
    @Mock
    ConsultQueueService queue;
    @Mock
    TriageService triageService;
    @Mock
    org.springframework.context.ApplicationEventPublisher events;
    @Mock
    com.tailtopia.vet.service.VetPresenceService presence;
    @Mock
    com.tailtopia.vet.service.VetAccountService vetAccounts;

    private ConsultSessionService service() {
        return new ConsultSessionService(repo, queue, triageService, events, presence, vetAccounts);
    }

    @Test
    void createWaitingPersistsAndEnqueuesWhenNoActive() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> {
            ConsultSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 11L); // 模拟 IDENTITY 落库分配主键
            return s;
        });

        CreateResult result = service().createWaiting(7L, ConsultSource.DIRECT);

        assertThat(result.alreadyActive()).isFalse();
        assertThat(result.session().getStatus()).isEqualTo(SessionStatus.WAITING);
        assertThat(result.session().getWaitingStartedAt()).isNotNull();
        verify(queue).enqueue(11L);
    }

    @Test
    void createWaitingReturnsExistingWhenAlreadyActive() {
        ConsultSession existing = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.of(existing));

        CreateResult result = service().createWaiting(7L, ConsultSource.DIRECT);

        assertThat(result.alreadyActive()).isTrue();
        assertThat(result.session()).isSameAs(existing);
        verify(repo, never()).save(any());
        verify(queue, never()).enqueue(anyLong());
    }

    @Test
    void cancelTransitionsToCancelledAndDequeues() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(presence.onlineVetIds()).thenReturn(java.util.List.of(1L, 2L));

        service().cancel(7L, 11L);

        assertThat(s.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        verify(queue).dequeue(11L);
        // Story 2.9：取消发失败请求事件（USER_CANCEL，含失败时刻在线兽医数）。
        verify(events).publishEvent(any(com.tailtopia.consult.event.ConsultRequestFailedEvent.class));
    }

    @Test
    void cancelRejectsForeignOwnerAsNotFound() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        when(repo.findById(11L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service().cancel(999L, 11L)).isInstanceOf(AppException.class);
        verify(queue, never()).dequeue(anyLong());
    }

    @Test
    void upgradeBindsAiContextSnapshotForYellow() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(triageService.getResultForUpgrade(7L, 99L)).thenReturn(
                new TriageUpgradeContext(99L, DangerLevel.YELLOW, "呕吐两次", List.of("k1", "k2")));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> {
            ConsultSession s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 21L);
            return s;
        });

        CreateResult result = service().createWaitingFromUpgrade(7L, 99L);

        assertThat(result.alreadyActive()).isFalse();
        assertThat(result.session().getSource()).isEqualTo(ConsultSource.AI_UPGRADE);
        assertThat(result.session().getAiDangerLevel()).isEqualTo("YELLOW");
        assertThat(result.session().getAiSymptomText()).isEqualTo("呕吐两次");
        assertThat(result.session().getAiImageRefs()).containsExactly("k1", "k2");
        assertThat(result.session().hasAiContext()).isTrue();
        verify(queue).enqueue(21L);
    }

    @Test
    void upgradeRejectsRedDangerLevel() {
        when(repo.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(triageService.getResultForUpgrade(7L, 99L)).thenReturn(
                new TriageUpgradeContext(99L, DangerLevel.RED, "抽搐", List.of()));

        assertThatThrownBy(() -> service().createWaitingFromUpgrade(7L, 99L))
                .isInstanceOf(AppException.class);
        verify(repo, never()).save(any());
        verify(queue, never()).enqueue(anyLong());
    }

    @Test
    void timedOutComputedFromWaitingStartButStaysWaiting() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        // 默认 startWaiting 计时刚开始 → 未超时
        assertThat(s.isTimedOut(ConsultSessionService.WAITING_TIMEOUT_SECONDS)).isFalse();
        // continueWaiting 重置计时基准（仍 WAITING）
        when(repo.findById(11L)).thenReturn(Optional.of(s));
        when(repo.save(any(ConsultSession.class))).thenAnswer(inv -> inv.getArgument(0));
        Instant before = s.getWaitingStartedAt();
        service().continueWaiting(7L, 11L);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.WAITING);
        assertThat(s.getWaitingStartedAt()).isAfterOrEqualTo(before);
    }

    // ===== 会话对端（兽医）身份富化（2026-08-07 bug：用户端顶栏显示的是另一个兽医的名字）=====

    @Test
    void vetPeerIsUnknownWhileWaitingSoNoVetLookupHappens() {
        ConsultSession waiting = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);

        assertThat(service().vetPeerOf(waiting))
                .isEqualTo(com.tailtopia.consult.dto.ConsultSessionResponse.VetPeer.UNKNOWN);
        // 尚无兽医就不该去查兽医账号（省一次跨模块查询，也避免 getById(null) 之类的坑）
        verify(vetAccounts, never()).getById(anyLong());
    }

    /**
     * 富化失败**必须**降级为 UNKNOWN，不得外抛。
     *
     * <p>为什么这条重要：会话页每 5s 轮询一次 {@code GET /consult-sessions/{id}}，顶栏身份只是
     * 装饰信息。若兽医账号查询的抖动能把这个接口打成 500，用户的聊天页会直接白屏 ——
     * 拿一个「显示谁」的小功能去换掉整个会话页的可用性，不划算。
     */
    @Test
    void vetPeerDegradesToUnknownWhenLookupFails() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        s.markInProgress(2L);
        when(vetAccounts.getById(2L)).thenThrow(new IllegalStateException("vet account lookup down"));

        assertThat(service().vetPeerOf(s))
                .isEqualTo(com.tailtopia.consult.dto.ConsultSessionResponse.VetPeer.UNKNOWN);
    }

    @Test
    void vetPeerCarriesTheAcceptingVetIdentityNotSomeoneElse() {
        ConsultSession s = ConsultSession.startWaiting(7L, ConsultSource.DIRECT);
        s.markInProgress(2L);
        com.tailtopia.vet.domain.VetAccount vet = com.tailtopia.vet.domain.VetAccount.create(
                "vettest1", "hash", "drh. Test Satu (vettest1)");
        vet.setAvatarUrl("https://cdn/v2.jpg");
        when(vetAccounts.getById(2L)).thenReturn(vet);
        when(presence.isOnline(2L)).thenReturn(true);

        var peer = service().vetPeerOf(s);

        // 身份必须来自**本会话的接单兽医**（vetId=2）。改前 App 端写死了另一个兽医的名字，
        // 现象看起来就像会话串号 —— 这条锁住「谁接单就显示谁」。
        assertThat(peer.displayName()).isEqualTo("drh. Test Satu (vettest1)");
        assertThat(peer.avatarUrl()).isEqualTo("https://cdn/v2.jpg");
        assertThat(peer.online()).isTrue();
        verify(vetAccounts).getById(2L);
    }
}

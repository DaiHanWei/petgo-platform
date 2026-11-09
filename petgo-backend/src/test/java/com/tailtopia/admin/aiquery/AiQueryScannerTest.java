package com.tailtopia.admin.aiquery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.aiquery.agent.AgentJob;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient.PollOutcome;
import com.tailtopia.admin.aiquery.agent.AiQueryAgentClient.SubmitOutcome;
import com.tailtopia.admin.aiquery.config.AiQueryProperties;
import com.tailtopia.admin.aiquery.domain.AiQueryConversation;
import com.tailtopia.admin.aiquery.domain.AiQueryLanguage;
import com.tailtopia.admin.aiquery.domain.AiQueryMessage;
import com.tailtopia.admin.aiquery.domain.AiQueryStatus;
import com.tailtopia.admin.aiquery.repository.AiQueryConversationRepository;
import com.tailtopia.admin.aiquery.repository.AiQueryMessageRepository;
import com.tailtopia.admin.aiquery.service.AiQueryScanner;
import com.tailtopia.admin.aiquery.service.AiQueryTransitions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：扫描器状态机 —— 开关、提交、完成、失败、未知任务、重复处理不重复动作。 */
class AiQueryScannerTest {

    AiQueryProperties props = new AiQueryProperties();
    AiQueryAgentClient agent = mock(AiQueryAgentClient.class);
    AiQueryMessageRepository messages = mock(AiQueryMessageRepository.class);
    AiQueryConversationRepository conversations = mock(AiQueryConversationRepository.class);
    AiQueryTransitions transitions = mock(AiQueryTransitions.class);
    AiQueryScanner scanner = new AiQueryScanner(props, agent, messages, conversations, transitions);

    AiQueryConversation conv = AiQueryConversation.start("conv-token-0123456789abcdef", 7, "q", AiQueryLanguage.ZH);
    AiQueryMessage pending = AiQueryMessage.pending("msg-token-0123456789abcdefgh", 1, "q");

    @BeforeEach
    void setUp() {
        props.setMode("on");
        ReflectionTestUtils.setField(conv, "id", 1L);
        ReflectionTestUtils.setField(conv, "agentSessionId", "sess-1");
        ReflectionTestUtils.setField(pending, "id", 10L);
        when(messages.findStale(any(), any())).thenReturn(List.of());
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.PENDING), any())).thenReturn(List.of());
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.RUNNING), any())).thenReturn(List.of());
        when(conversations.findAllById(any())).thenReturn(List.of(conv));
    }

    private AiQueryMessage running() {
        AiQueryMessage m = AiQueryMessage.pending("run-token-0123456789abcdefgh", 1, "q");
        ReflectionTestUtils.setField(m, "id", 11L);
        ReflectionTestUtils.setField(m, "status", AiQueryStatus.RUNNING);
        return m;
    }

    @Test
    void offDoesNotSubmitOrPoll() {
        props.setMode("off");
        scanner.scan();
        verifyNoInteractions(agent, messages, transitions);
    }

    @Test
    void acceptedPendingBecomesRunningWithResumeSessionAndLanguageCode() {
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.PENDING), any())).thenReturn(List.of(pending));
        when(agent.submit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(SubmitOutcome.ACCEPTED);
        scanner.scan();
        verify(agent).submit(pending.getToken(), conv.getToken(), "q", "zh", "sess-1");
        verify(transitions).running(pending);
    }

    @Test
    void retryKeepsPendingAndStopsTheRound() {
        AiQueryMessage second = AiQueryMessage.pending("msg-token-second-0123456789", 1, "q2");
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.PENDING), any())).thenReturn(List.of(pending, second));
        when(agent.submit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(SubmitOutcome.RETRY);
        scanner.scan();
        verify(agent, times(1)).submit(anyString(), anyString(), anyString(), anyString(), any());
        verify(transitions, never()).running(any());
        verify(transitions, never()).failed(any(), any(), any(), any());
    }

    @Test
    void rejectedSubmissionFails() {
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.PENDING), any())).thenReturn(List.of(pending));
        when(agent.submit(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(SubmitOutcome.REJECTED);
        scanner.scan();
        verify(transitions).failed(eq(pending), eq(AiQueryStatus.PENDING), eq(AiQueryScanner.ERR_AGENT_REJECTED), isNull());
    }

    @Test
    void pollOutcomes() {
        AiQueryMessage m = running();
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.RUNNING), any())).thenReturn(List.of(m));

        AgentJob done = new AgentJob("DONE", "a", "s", List.of(), 1, 1, null, null);
        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Found(done));
        scanner.scan();
        verify(transitions).done(m, done);

        AgentJob failed = new AgentJob("FAILED", null, null, List.of(), 1, 1, null, "AGENT_RATE_LIMITED");
        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Found(failed));
        scanner.scan();
        verify(transitions).failed(m, AiQueryStatus.RUNNING, "AGENT_RATE_LIMITED", failed);

        AgentJob noCode = new AgentJob("FAILED", null, null, List.of(), 1, 1, null, "SOMETHING_NEW_AND_VERY_LONG_ERROR_CODE_FROM_AGENT");
        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Found(noCode));
        scanner.scan();
        verify(transitions).failed(m, AiQueryStatus.RUNNING, "AGENT_ERROR", noCode);

        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Unknown());
        scanner.scan();
        verify(transitions).failed(m, AiQueryStatus.RUNNING, AiQueryScanner.ERR_AGENT_LOST, null);

        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Found(new AgentJob("RUNNING", null, null, null, 2, null, null, null)));
        scanner.scan();
        when(agent.poll(m.getToken())).thenReturn(new PollOutcome.Unreachable());
        scanner.scan();
        verify(transitions, times(1)).done(any(), any());
        // 文件大小缺省按 0
        assertThat(new AgentJob("DONE", "a", null, List.of(new AgentJob.AgentFile("a.csv", null)), 1, 1, null, null)
                .filesOrEmpty().get(0).size()).isZero();
        verify(transitions, times(3)).failed(any(), any(), any(), any());
    }

    @Test
    void unreachableStopsTheRoundInsteadOfWaitingOnEveryJob() {
        AiQueryMessage a = running();
        AiQueryMessage b = AiQueryMessage.pending("run-token-second-0123456789ab", 1, "q");
        ReflectionTestUtils.setField(b, "status", AiQueryStatus.RUNNING);
        when(messages.findByStatusOrderByIdAsc(eq(AiQueryStatus.RUNNING), any())).thenReturn(List.of(a, b));
        when(agent.poll(anyString())).thenReturn(new PollOutcome.Unreachable());
        scanner.scan();
        verify(agent, times(1)).poll(anyString());
    }

    @Test
    void staleTasksTimeOut() {
        when(messages.findStale(any(), any())).thenReturn(List.of(pending));
        scanner.scan();
        verify(transitions).timeout(eq(pending), any());
    }

    @Test
    void costIsRoundedAndOutOfRangeDropped() throws Exception {
        var m = AiQueryTransitions.class.getDeclaredMethod("cost", java.math.BigDecimal.class);
        m.setAccessible(true);
        assertThat(m.invoke(null, new java.math.BigDecimal("0.12345"))).hasToString("0.1235");
        assertThat(m.invoke(null, new java.math.BigDecimal("12345678"))).isNull();
        assertThat(m.invoke(null, (Object) null)).isNull();
    }
}

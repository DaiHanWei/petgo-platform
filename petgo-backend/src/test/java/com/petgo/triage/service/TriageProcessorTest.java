package com.petgo.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.petgo.shared.ai.GeminiClient;
import com.petgo.shared.ai.GeminiException;
import com.petgo.shared.ai.GeminiTriageResult;
import com.petgo.shared.media.SignedUrlService;
import com.petgo.triage.TriageTestSupport;
import com.petgo.triage.domain.DangerLevel;
import com.petgo.triage.domain.TriageStatus;
import com.petgo.triage.domain.TriageTask;
import com.petgo.triage.repository.TriageTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：状态机成功落 DONE（AC1）+ 失败重试 ≤3 后 FAILED（AC3）+ 终态幂等。 */
class TriageProcessorTest {

    private TriageTaskRepository tasks;
    private SignedUrlService signedUrlService;
    private GeminiClient geminiClient;
    private SafetyRuleLayer safetyRuleLayer;
    private TriageProcessor processor;

    @BeforeEach
    void setUp() {
        tasks = mock(TriageTaskRepository.class);
        signedUrlService = mock(SignedUrlService.class);
        geminiClient = mock(GeminiClient.class);
        safetyRuleLayer = new SafetyRuleLayer();
        processor = new TriageProcessor(tasks, signedUrlService, geminiClient, safetyRuleLayer);
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void successWritesDoneWithDangerLevelAndParsed() {
        TriageTask task = TriageTestSupport.task(1L, 7L, TriageStatus.PENDING, "咳嗽", List.of("k1"));
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(signedUrlService.signAll(List.of("k1"))).thenReturn(List.of("https://signed/k1"));
        when(geminiClient.analyze(anyString(), anyList())).thenReturn(
                new GeminiTriageResult("YELLOW", "尽快就医", null, "仅供参考", Map.of("x", 1)));

        processor.process(1L);

        assertThat(task.getStatus()).isEqualTo(TriageStatus.DONE);
        assertThat(task.getDangerLevel()).isEqualTo(DangerLevel.YELLOW);
        assertThat(task.getParsedResult()).containsEntry("advice", "尽快就医");
        assertThat(task.getGeminiRaw()).containsEntry("x", 1);
    }

    @Test
    void retriesThenFailsAfterMaxRetry() {
        TriageTask task = TriageTestSupport.task(2L, 7L, TriageStatus.PENDING, "x", null);
        when(tasks.findById(2L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList())).thenThrow(new GeminiException("timeout"));

        processor.process(2L);

        assertThat(task.getStatus()).isEqualTo(TriageStatus.FAILED);
        assertThat(task.getRetryCount()).isGreaterThan(3);
    }

    @Test
    void terminalTaskIsSkippedIdempotently() {
        TriageTask done = TriageTestSupport.task(3L, 7L, TriageStatus.DONE, "x", null);
        when(tasks.findById(3L)).thenReturn(Optional.of(done));

        processor.process(3L);

        assertThat(done.getStatus()).isEqualTo(TriageStatus.DONE);
        org.mockito.Mockito.verifyNoInteractions(geminiClient);
    }

    @Test
    void missingTaskIsNoOp() {
        when(tasks.findById(4L)).thenReturn(Optional.empty());
        processor.process(4L); // 不抛
        org.mockito.Mockito.verifyNoInteractions(geminiClient);
    }
}

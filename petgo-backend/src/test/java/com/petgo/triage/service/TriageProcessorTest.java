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
        safetyRuleLayer.load(); // 加载真实高危清单，验整链路升红
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
    void safetyRuleForcesRedEvenWhenModelSaysGreen() {
        // 整链路：模型假阴性给 GREEN，但症状命中高危清单 → 最终落库 RED（AC2）。
        TriageTask task = TriageTestSupport.task(9L, 7L, TriageStatus.PENDING, "狗误食巧克力", null);
        when(tasks.findById(9L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", Map.of()));

        processor.process(9L);

        assertThat(task.getStatus()).isEqualTo(TriageStatus.DONE);
        assertThat(task.getDangerLevel()).isEqualTo(DangerLevel.RED);
        assertThat(task.getParsedResult()).containsEntry("escalatedBySafetyRule", true);
        assertThat(task.getParsedResult().get("matchedSafetyRuleIds").toString())
                .contains("chocolate_ingestion");
    }

    @Test
    void nonHighRiskKeepsModelGreen() {
        // 反向：非高危 + 模型 GREEN → 保持 GREEN（不误升，避免红色滥用）。
        TriageTask task = TriageTestSupport.task(10L, 7L, TriageStatus.PENDING, "轻微打喷嚏", null);
        when(tasks.findById(10L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", Map.of()));

        processor.process(10L);

        assertThat(task.getDangerLevel()).isEqualTo(DangerLevel.GREEN);
        assertThat(task.getParsedResult()).containsEntry("escalatedBySafetyRule", false);
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

package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.ai.GeminiClient;
import com.tailtopia.shared.ai.GeminiException;
import com.tailtopia.shared.ai.GeminiTriageResult;
import com.tailtopia.shared.ai.TriagePetContext;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.triage.TriageTestSupport;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.repository.TriageTaskRepository;
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
    private PetProfileQueryService petQuery;
    private TriageProcessor processor;

    @BeforeEach
    void setUp() {
        tasks = mock(TriageTaskRepository.class);
        signedUrlService = mock(SignedUrlService.class);
        geminiClient = mock(GeminiClient.class);
        safetyRuleLayer = new SafetyRuleLayer();
        safetyRuleLayer.load(); // 加载真实高危清单，验整链路升红
        petQuery = mock(PetProfileQueryService.class);
        processor = new TriageProcessor(tasks, signedUrlService, geminiClient, safetyRuleLayer, petQuery);
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void successWritesDoneWithDangerLevelAndParsed() {
        TriageTask task = TriageTestSupport.task(1L, 7L, TriageStatus.PENDING, "咳嗽", List.of("k1"));
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(signedUrlService.signAll(List.of("k1"))).thenReturn(List.of("https://signed/k1"));
        when(geminiClient.analyze(anyString(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("YELLOW", "尽快就医", null, "仅供参考", null, null, null, Map.of("x", 1)));

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
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

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
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

        processor.process(10L);

        assertThat(task.getDangerLevel()).isEqualTo(DangerLevel.GREEN);
        assertThat(task.getParsedResult()).containsEntry("escalatedBySafetyRule", false);
    }

    @Test
    void retriesThenFailsAfterMaxRetry() {
        TriageTask task = TriageTestSupport.task(2L, 7L, TriageStatus.PENDING, "x", null);
        when(tasks.findById(2L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenThrow(new GeminiException("timeout"));

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

    // ---- V1.4.0 multi-pet Story 4.1 · AC3：分诊安全回归（有 / 无宠物上下文同一裁决）----

    private static final TriagePetContext PET_CTX =
            new TriagePetContext("DOG", 38, new java.math.BigDecimal("12.40"));

    private static TriageTask taskWithPet(long id, String symptom, Long petId) {
        TriageTask t = TriageTask.submit(7L, petId, symptom, null, null, "en");
        TriageTestSupport.set(t, "id", id);
        TriageTestSupport.set(t, "status", TriageStatus.PENDING);
        return t;
    }

    /** 跑一次处理，返回 {@code enforce} 收到的原始参数（modelLevel + 文本源数组）。 */
    private Object[] runAndCaptureEnforceArgs(TriageTask task, String modelLevel) {
        SafetyRuleLayer spy = org.mockito.Mockito.spy(safetyRuleLayer);
        TriageProcessor p = new TriageProcessor(tasks, signedUrlService, geminiClient, spy, petQuery);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult(modelLevel, "建议观察", "无", "仅供参考", null, null, null, Map.of()));
        p.process(task.getId());
        var calls = org.mockito.Mockito.mockingDetails(spy).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("enforce"))
                .toList();
        assertThat(calls).hasSize(1);
        return calls.get(0).getRawArguments();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "model={0} symptom={1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "GREEN, 轻微打喷嚏",
            "YELLOW, 轻微打喷嚏",
            "RED, 轻微打喷嚏",
            "GREEN, 狗误食巧克力",
            "YELLOW, 狗误食巧克力",
            "RED, 狗误食巧克力"})
    void petContextNeverChangesSafetyVerdict(String modelLevel, String symptom) {
        when(petQuery.findTriageContext(7L, 20L)).thenReturn(Optional.of(PET_CTX));
        TriageTask without = taskWithPet(100L, symptom, null);
        TriageTask with = taskWithPet(101L, symptom, 20L);

        Object[] argsWithout = runAndCaptureEnforceArgs(without, modelLevel);
        Object[] argsWith = runAndCaptureEnforceArgs(with, modelLevel);

        // enforce 参数逐字相同：宠物上下文不是安全层的信号源。
        assertThat(argsWith).containsExactly(argsWithout);
        assertThat(with.getStatus()).isEqualTo(TriageStatus.DONE);
        assertThat(with.getDangerLevel()).isEqualTo(without.getDangerLevel());
        assertThat(with.getParsedResult().get("dangerLevel")).isEqualTo(without.getParsedResult().get("dangerLevel"));
        assertThat(with.getParsedResult().get("escalatedBySafetyRule"))
                .isEqualTo(without.getParsedResult().get("escalatedBySafetyRule"));
        // 模型侧：无 petId 传 null，有 petId 传所选宠物上下文。
        org.mockito.Mockito.verify(geminiClient).analyze(eq(symptom), anyList(), any(), org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.verify(geminiClient).analyze(eq(symptom), anyList(), any(), eq(PET_CTX));
    }

    @Test
    void safetyRuleStillForcesRedWithPetContext() {
        when(petQuery.findTriageContext(7L, 20L)).thenReturn(Optional.of(PET_CTX));
        TriageTask task = taskWithPet(102L, "狗误食巧克力", 20L);
        when(tasks.findById(102L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

        processor.process(102L);

        assertThat(task.getDangerLevel()).isEqualTo(DangerLevel.RED);
        assertThat(task.getParsedResult()).containsEntry("escalatedBySafetyRule", true);
    }

    @Test
    void deletedPetFallsBackToNoContextAndStillDone() {
        when(petQuery.findTriageContext(7L, 20L)).thenReturn(Optional.empty());
        TriageTask task = taskWithPet(103L, "轻微打喷嚏", 20L);
        when(tasks.findById(103L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

        processor.process(103L);

        assertThat(task.getStatus()).isEqualTo(TriageStatus.DONE);
        org.mockito.Mockito.verify(geminiClient)
                .analyze(eq("轻微打喷嚏"), anyList(), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void taskWithoutPetDoesNotQueryPetContext() {
        TriageTask task = taskWithPet(104L, "轻微打喷嚏", null);
        when(tasks.findById(104L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

        processor.process(104L);

        org.mockito.Mockito.verifyNoInteractions(petQuery);
    }

    /** code review 4-1 B1：上下文读取抛异常 → 不打断状态机，按 null 上下文照常 DONE。 */
    @Test
    void petContextFailureDoesNotBreakStateMachine() {
        when(petQuery.findTriageContext(7L, 20L)).thenThrow(new IllegalStateException("db down"));
        TriageTask task = taskWithPet(105L, "轻微打喷嚏", 20L);
        when(tasks.findById(105L)).thenReturn(Optional.of(task));
        when(geminiClient.analyze(any(), anyList(), any(), any())).thenReturn(
                new GeminiTriageResult("GREEN", "继续观察", null, "仅供参考", null, null, null, Map.of()));

        processor.process(105L);

        assertThat(task.getStatus()).isEqualTo(TriageStatus.DONE);
        assertThat(task.getRetryCount()).isZero();
        org.mockito.Mockito.verify(geminiClient)
                .analyze(eq("轻微打喷嚏"), anyList(), any(), org.mockito.ArgumentMatchers.isNull());
    }

    /** code review 4-1 B2：上下文 toString 不带取值。 */
    @Test
    void petContextToStringIsRedacted() {
        assertThat(PET_CTX.toString()).isEqualTo("TriagePetContext[redacted]")
                .doesNotContain("12.4").doesNotContain("38").doesNotContain("DOG");
    }
}

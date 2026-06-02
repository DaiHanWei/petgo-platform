package com.petgo.triage.service;

import com.petgo.shared.ai.GeminiClient;
import com.petgo.shared.ai.GeminiTriageResult;
import com.petgo.shared.media.SignedUrlService;
import com.petgo.triage.domain.DangerLevel;
import com.petgo.triage.domain.TriageStatus;
import com.petgo.triage.domain.TriageTask;
import com.petgo.triage.repository.TriageTaskRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分诊状态机处理器（Story 4.1，AC1/AC3）。领取任务 → 置 PROCESSING → 为每张私密图取短 TTL 签名 URL
 * → 调 {@link GeminiClient} → 经 {@link SafetyRuleLayer} 后置裁决（4.2 强制升红挂载点）→ 写
 * {@code gemini_raw}/{@code parsed_result}/{@code danger_level} → 置 DONE。
 *
 * <p>失败：超时/异常 → retry_count++ 重试（≤3）；超限置 FAILED 供前端降级。**禁 MQ**，全靠 DB 状态机。
 *
 * <p>🔒 护栏：
 * <ul>
 *   <li>后置升红是<b>唯一、显式、不可旁路</b>的一处（markDone 前必经 {@link SafetyRuleLayer#enforce}）。</li>
 *   <li>签名 URL 仅在内存中传给 Gemini，<b>绝不入库、绝不落日志</b>；任务只存对象 key。</li>
 *   <li>失败日志只记异常类名，<b>不落</b> 症状 / 图片 / 签名 URL / 解析结果 / Gemini key。</li>
 * </ul>
 */
@Service
public class TriageProcessor {

    private static final Logger log = LoggerFactory.getLogger(TriageProcessor.class);
    private static final int MAX_RETRY = 3;

    private final TriageTaskRepository tasks;
    private final SignedUrlService signedUrlService;
    private final GeminiClient geminiClient;
    private final SafetyRuleLayer safetyRuleLayer;

    public TriageProcessor(TriageTaskRepository tasks, SignedUrlService signedUrlService,
            GeminiClient geminiClient, SafetyRuleLayer safetyRuleLayer) {
        this.tasks = tasks;
        this.signedUrlService = signedUrlService;
        this.geminiClient = geminiClient;
        this.safetyRuleLayer = safetyRuleLayer;
    }

    /**
     * 处理单个任务（幂等：已 DONE/FAILED 直接跳过）。由事件监听 / 启动重扫驱动。
     * 内部按 DB 状态机重试 ≤3，超限置 FAILED。
     */
    @Transactional
    public void process(long triageId) {
        TriageTask task = tasks.findById(triageId).orElse(null);
        if (task == null) {
            return;
        }
        if (task.getStatus() == TriageStatus.DONE || task.getStatus() == TriageStatus.FAILED) {
            return; // 幂等：终态不重复处理
        }
        task.markProcessing();
        tasks.save(task);

        while (true) {
            try {
                List<String> signedUrls = signImages(task.getImageObjectKeys());
                GeminiTriageResult result = geminiClient.analyze(task.getSymptomText(), signedUrls);

                DangerLevel modelLevel = DangerLevel.fromNullable(result.dangerLevel());
                // === Story 4.2 后置强制升红的唯一挂载点（只升不降、不可旁路）===
                // 双源匹配：症状原文 + Gemini 解析文本（建议/用药参考），防模型漏读用户原话。
                SafetyDecision decision = safetyRuleLayer.enforce(
                        modelLevel, task.getSymptomText(), parsedText(result));

                task.markDone(decision.finalLevel(), result.raw(), toParsed(result, decision));
                tasks.save(task);
                return;
            } catch (RuntimeException e) {
                task.markRetry(); // retry_count++ + 置回 PENDING
                log.warn("分诊处理失败 triageId={} retryCount={} cause={}",
                        triageId, task.getRetryCount(), e.getClass().getSimpleName());
                if (task.getRetryCount() > MAX_RETRY) {
                    task.markFailed();
                    tasks.save(task);
                    return;
                }
            }
        }
    }

    private List<String> signImages(List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return List.of();
        }
        return signedUrlService.signAll(objectKeys);
    }

    /** Gemini 解析文本（建议 + 用药参考）作为安全层匹配的第二信号源。 */
    private static String parsedText(GeminiTriageResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.advice() != null) {
            sb.append(r.advice()).append(' ');
        }
        if (r.medicationRef() != null) {
            sb.append(r.medicationRef());
        }
        return sb.toString();
    }

    /**
     * 解析结果落 parsed_result JSONB（dangerLevel 用后置裁决后的最终值）。
     * 审计仅落「是否升红 + 命中规则 id」，**不落症状健康数据**（Story 4.2 护栏）。
     */
    private static Map<String, Object> toParsed(GeminiTriageResult r, SafetyDecision decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        DangerLevel finalLevel = decision.finalLevel();
        m.put("dangerLevel", finalLevel == null ? null : finalLevel.name());
        if (r.advice() != null) {
            m.put("advice", r.advice());
        }
        if (r.medicationRef() != null) {
            m.put("medicationRef", r.medicationRef());
        }
        if (r.disclaimer() != null) {
            m.put("disclaimer", r.disclaimer());
        }
        // 审计标识（非健康数据）：是否因规则层升红 + 命中规则 id。
        m.put("escalatedBySafetyRule", decision.escalatedBySafetyRule());
        if (!decision.matchedRuleIds().isEmpty()) {
            m.put("matchedSafetyRuleIds", decision.matchedRuleIds());
        }
        return m;
    }
}

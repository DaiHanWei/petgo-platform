package com.petgo.triage.dto;

import com.petgo.triage.domain.DangerLevel;
import com.petgo.triage.domain.TriageStatus;
import com.petgo.triage.domain.TriageTask;
import java.util.Map;

/**
 * 分诊结果响应（Story 4.1）。短轮询 {@code GET /triage/{id}} 两态：
 * <ul>
 *   <li>处理中（PENDING/PROCESSING）/ 失败（FAILED）→ 仅回 {@code status}（Jackson NON_NULL 省略其余）。</li>
 *   <li>就绪（DONE）→ 回完整结构（级别 + 建议 + 用药参考 + 免责声明）。</li>
 * </ul>
 *
 * <p>结构留足 4.4 三态展示字段；{@code disclaimer} 承载免责声明前置（NFR-9，实际前置展示在 4.4）。
 * {@code dangerLevel} 为经后置校验后落库的<b>最终值</b>（4.2 只升不降），非模型快路径值。
 */
public record TriageResultResponse(
        TriageStatus status,
        DangerLevel dangerLevel,
        String advice,
        String medicationRef,
        String disclaimer) {

    public static TriageResultResponse from(TriageTask t) {
        if (t.getStatus() != TriageStatus.DONE) {
            // 处理中 / 失败：仅回 status，不泄露未定级别。
            return new TriageResultResponse(t.getStatus(), null, null, null, null);
        }
        Map<String, Object> p = t.getParsedResult();
        return new TriageResultResponse(
                t.getStatus(),
                t.getDangerLevel(),
                str(p, "advice"),
                str(p, "medicationRef"),
                str(p, "disclaimer"));
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}

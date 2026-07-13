package com.tailtopia.triage.dto;

import com.tailtopia.shared.ai.TriageObservation;
import com.tailtopia.triage.domain.DangerLevel;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.domain.TriageTask;
import com.tailtopia.triage.domain.UnlockSource;
import java.util.List;
import java.util.Map;

/**
 * 分诊结果响应（Story 4.1；Story 2.2 加解锁分字段下发）。短轮询 {@code GET /triage/{id}} 两态：
 * <ul>
 *   <li>处理中（PENDING/PROCESSING）/ 失败（FAILED）→ 仅回 {@code status}（Jackson NON_NULL 省略其余，
 *       含 {@code unlockSource}/{@code locked}）。</li>
 *   <li>就绪（DONE）→ 回结构：<b>安全免费部分</b>始终下发 + <b>详建锁定部分</b>按解锁下发。</li>
 * </ul>
 *
 * <p><b>Story 2.2 分字段下发（FR-43A/43C，C-7）</b>：
 * <ul>
 *   <li><b>安全免费</b>（永不受锁影响）：{@code dangerLevel}（等级图标/颜色）、{@code disclaimer}（免责）、
 *       {@code observation}（黄色三要素含 {@code timeWindow} 时效）、{@code emergencySteps}/{@code emergencyAvoid}
 *       （红色对症强提醒）。安全信息永不被付费墙挡住。</li>
 *   <li><b>详建锁定</b>（SARAN PERAWATAN）：{@code advice}/{@code medicationRef} 仅在已解锁时下发，
 *       锁定时置 null（NON_NULL 省略）。{@code locked} 布尔供前端渲染 paywall。</li>
 * </ul>
 *
 * <p>🔒 <b>红色评级详建永不锁（FR-43C，安全单点）</b>：见 {@link #isUnlocked} —— {@code dangerLevel==RED}
 * 时无条件放行详建，与 {@code unlock_source} 无关、即使额度耗尽。这是红色永不锁的<b>唯一判定点</b>，
 * 不在别处埋绕过分支（比照 4.2 {@code SafetyRuleLayer} 只升不降、不可旁路）。
 *
 * <p>{@code locked}/{@code unlockSource} 用装箱类型：非 DONE 时为 null，NON_NULL 省略（保「未就绪仅回 status」契约）。
 * {@code dangerLevel} 为经后置校验后落库的<b>最终值</b>（4.2 只升不降），非模型快路径值。
 */
public record TriageResultResponse(
        TriageStatus status,
        DangerLevel dangerLevel,
        String advice,
        String medicationRef,
        String disclaimer,
        TriageObservation observation,
        List<String> emergencySteps,
        List<String> emergencyAvoid,
        UnlockSource unlockSource,
        Boolean locked) {

    public static TriageResultResponse from(TriageTask t) {
        if (t.getStatus() != TriageStatus.DONE) {
            // 处理中 / 失败：仅回 status，不泄露未定级别/锁态。
            return new TriageResultResponse(
                    t.getStatus(), null, null, null, null, null, null, null, null, null);
        }
        Map<String, Object> p = t.getParsedResult();
        boolean unlocked = isUnlocked(t);
        return new TriageResultResponse(
                t.getStatus(),
                t.getDangerLevel(),
                unlocked ? str(p, "advice") : null,          // 详建锁定：仅解锁时下发
                unlocked ? str(p, "medicationRef") : null,   // 详建锁定：仅解锁时下发
                str(p, "disclaimer"),                         // 安全免费：永不锁
                observation(p),                               // 安全免费：黄色三要素
                strList(p, "emergencySteps"),                 // 安全免费：红色强提醒
                strList(p, "emergencyAvoid"),                 // 安全免费：红色强提醒
                t.getUnlockSource(),                          // 当前锁态来源（null 按 LOCKED 语义）
                !unlocked);                                   // 前端 paywall 渲染依据
    }

    /**
     * 🔒 详建是否放行——<b>红色永不锁的唯一判定点</b>（FR-43C 安全单点，不可旁路）。
     * {@code dangerLevel==RED} 恒放行（即使 {@code unlock_source=LOCKED}、即使额度耗尽）；否则看解锁来源。
     */
    private static boolean isUnlocked(TriageTask t) {
        if (t.getDangerLevel() == DangerLevel.RED) {
            return true; // 红色详建永不锁——与 unlock_source 无关
        }
        UnlockSource src = t.getUnlockSource();
        return src == UnlockSource.FREE_QUOTA || src == UnlockSource.PAID;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> map, String key) {
        if (map == null || !(map.get(key) instanceof List<?> list)) {
            return null;
        }
        return (List<String>) list;
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static TriageObservation observation(Map<String, Object> p) {
        if (p == null || !(p.get("observation") instanceof Map<?, ?> obs)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) obs;
        return new TriageObservation(
                (List<String>) m.get("indicators"),
                m.get("timeWindow") == null ? null : m.get("timeWindow").toString(),
                (List<String>) m.get("escalationTriggers"));
    }
}

package com.tailtopia.consult.dto;

/**
 * 未评问诊原因（Story 6.2，AB-6B）。**按实际数据模型如实映射，不杜撰**：
 * <ul>
 *   <li>{@link #TIMEOUT_UNRATED}：CLOSED 且 closed_reason=UNRATED 且无评分行——30 分钟评分门超时，
 *       用户未在评分窗口内评分（覆盖 PRD 所述「用户未评分 / 30 分钟超时」，二者同源）。</li>
 *   <li>{@link #INTERRUPTED}：会话中断（兽医封禁），从不进入评分流程——数据中真实存在的第三类未评。</li>
 * </ul>
 */
public enum UnratedReason {
    TIMEOUT_UNRATED,
    INTERRUPTED
}

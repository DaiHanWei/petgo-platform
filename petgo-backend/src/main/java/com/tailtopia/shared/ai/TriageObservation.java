package com.tailtopia.shared.ai;

import java.util.List;

/**
 * 黄色「条件倒计时协议」三要素（Story 4.2/4.4 · FR-2）。由模型结构化产出，前端按结构分区呈现，
 * 不靠前端从自由文本切分。
 *
 * @param indicators         具体观察指标（如「精神状态」「进食量」）
 * @param timeWindow         观察时间窗口（如「未来 12 小时」）
 * @param escalationTriggers 升级触发条件（出现即应就医）
 */
public record TriageObservation(
        List<String> indicators,
        String timeWindow,
        List<String> escalationTriggers) {
}

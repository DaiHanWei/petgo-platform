package com.tailtopia.tailsonality.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Diary 时间线的 Tailsonality 解锁只读视图（V1.3.2 Story 3.3 · AD-9）。
 *
 * @param resultId    结果 id（仅用于同刻排序与分页，<b>不对外下发</b>）
 * @param unlockedAt  解锁时刻（UTC，排序键与有效日期都按它）
 * @param resultToken 结果 token（作者态点击进结果页）
 * @param code        完整代号 {@code ENTJ-H}
 * @param testedOn    测试日期（{@code created_at} 的 UTC 日，仅显示）
 */
public record TailsonalityTimelineView(long resultId, Instant unlockedAt, String resultToken, String code,
        LocalDate testedOn) {
}

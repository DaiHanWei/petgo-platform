package com.petgo.consult.domain;

/**
 * 评分补弹标记流转（Story 5.6，FR-33）。
 *
 * <ul>
 *   <li>{@link #NONE}：无需补弹（已评分或仍进行中）。</li>
 *   <li>{@link #PENDING}：超时未评 → 下次进问诊页补弹一次。</li>
 *   <li>{@link #PROMPTED}：已补弹过 → 不再弹（仍可评，跳过则永久未评分）。</li>
 * </ul>
 */
public enum RatingPromptState {
    NONE,
    PENDING,
    PROMPTED
}

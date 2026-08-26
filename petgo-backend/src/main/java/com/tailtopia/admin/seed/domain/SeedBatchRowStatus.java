package com.tailtopia.admin.seed.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 批量内容行的状态（V1.1.6 Story 13.1 · AB-3K/3L）。
 *
 * <p>🛡 <b>状态挂在行上，批次不持有状态</b>（AC2）。「47 已发布 / 5 排期中 / 3 待修正」
 * 是常态而非异常 —— 按批次级状态实现就得为这类组合定义一堆合成态，
 * 而行级操作（改某一条的计划时间、修某一条的校验错误）根本无处落脚。
 *
 * <h2>合法流转</h2>
 * <pre>
 *   DRAFT ──校验通过──→ VALIDATED ──排期──→ SCHEDULED ──到点──→ PUBLISHED
 *     ↑                    │                  │                  ✗ 终态
 *     │                    │立即发布           └──失败──→ FAILED
 *     │                    ↓                                      │
 *     │                 PUBLISHED                                 │
 *     └────────改内容 / 取消排期 / 修错重提────────────────────────┘
 * </pre>
 *
 * <p>⚠️ <b>{@code VALIDATED → PUBLISHED} 是刻意允许的</b>。AC1 把状态链写成
 * 「校验通过 → 已排期 → 已发布」，但"确认发布"（13-4）是**立即**发布 ——
 * 硬走 SCHEDULED 就得编一个假的计划时间，于是排期列表里会出现一堆从未被排期的行。
 *
 * <p>⚠️ <b>{@code VALIDATED → DRAFT} 也允许</b>：校验通过之后又改了内容，必须重新校验。
 * 不允许的话运营改完一个字就得整批重来。
 *
 * <p>🛡 <b>{@link #PUBLISHED} 是终态</b>：内容已经在 content_posts 里、已经对外可见了，
 * 把行改回草稿不会让那条内容消失，只会让后台显示与线上事实不一致。
 * 「整批撤回」是另一件事（本版本不做，OQ-22 后移），它删的是内容而不是改行状态。
 */
public enum SeedBatchRowStatus {

    /** 录入中 / 校验未通过。{@code error_message} 存校验错误。 */
    DRAFT,

    /** 校验通过、等运营确认。 */
    VALIDATED,

    /** 已排期、等到点。{@code scheduled_at} 必须非空。 */
    SCHEDULED,

    /** 已发布。**终态**，{@code content_post_id} 已回填。 */
    PUBLISHED,

    /** 发布失败。{@code error_message} 存原因。可修错重提回 DRAFT。 */
    FAILED;

    private static final Map<SeedBatchRowStatus, Set<SeedBatchRowStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(VALIDATED),
            VALIDATED, EnumSet.of(SCHEDULED, PUBLISHED, DRAFT),
            SCHEDULED, EnumSet.of(PUBLISHED, FAILED, DRAFT),
            PUBLISHED, EnumSet.noneOf(SeedBatchRowStatus.class),
            FAILED, EnumSet.of(DRAFT));

    /** 能否从本状态流转到 {@code target}。 */
    public boolean canGoTo(SeedBatchRowStatus target) {
        return target != null && ALLOWED.get(this).contains(target);
    }

    /** 是否还"没发出去" —— 也就是会被 13-5 的到点扫描 / 12-1 的排期计数看到的那些。 */
    public boolean isPending() {
        return this == DRAFT || this == VALIDATED || this == SCHEDULED;
    }
}

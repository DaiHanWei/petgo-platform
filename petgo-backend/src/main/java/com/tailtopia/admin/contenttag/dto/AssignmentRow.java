package com.tailtopia.admin.contenttag.dto;

import java.time.Instant;

/**
 * 分配记录的一行（Story 11.2 · AB-10C）。
 *
 * @param permanent {@code endsAt} 为空 = 永久分配（本表比顶置排期多这一种情况）
 */
public record AssignmentRow(long id, long postId, String postSummary,
        long tagId, String tagName,
        Instant startsAt, Instant endsAt,
        /** 打标时刻（V1.3.0 Story 7.4 · AC3；与 startsAt 不是一回事：可以先排期、后生效）。 */
        Instant createdAt,
        /**
         * 操作人显示名（Story 7.4 · AC3）。
         *
         * <p>⚠️ 分配表上**没有操作人列** —— 这里取自审计日志里那条 {@code CONTENT_TAG_ASSIGN}
         * （target = 本条分配 id）。所以通过后台打的标都有，而更早、经其它路径落库的历史记录会是空，
         * 界面上显示「—」。要让它恒有值得给表加列 + 迁移，超出本 story 的范围（Dev Notes 明确说
         * 增量只是「状态列 + 挪进抽屉」），已记入 Completion Notes 待拍板。
         */
        String actorName,
        /** 当前是否生效（{@code [starts_at, ends_at)} 内）。 */
        boolean active,
        /**
         * 尚未开始（{@code startsAt > now}）。
         *
         * <p>🔴 状态必须是**三态**，不能只有「生效中 / 已到期」两档：本页明确支持「先排期、后生效」
         * （打标表单的开始时间可以填未来，{@link #createdAt} 与它不是一回事）。把一条排在下周的分配
         * 写成「已到期」，运营的合理解读是「排期作废了」，接着会给同一条内容再打一次标。
         */
        boolean pending) {

    public boolean permanent() {
        return endsAt == null;
    }

    /** 已到期：既非生效中、也不是还没开始。 */
    public boolean expired() {
        return !active && !pending;
    }
}

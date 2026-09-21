package com.tailtopia.content.event;

import java.time.Instant;
import java.util.List;

/**
 * 有人在正文 / 评论里 @ 了一批人（V1.3.0 batch-b1 Story 3.4，过去式）。
 * 经 {@code ApplicationEventPublisher} 进程内发布，供 notify 模块消费 —— <b>content 不直调 notify</b>。
 *
 * <h2>🔴 发布时机 = 那条内容 / 评论**变成别人看得见的那一刻**</h2>
 * 不是「提交的那一刻」。三个落点：
 * <ol>
 *   <li>{@code ContentService.publish} 的**正常分支**（落 PUBLISHED）；</li>
 *   <li>{@code ContentService.approveReview}（挂起帖人工过审 → PUBLISHED）；</li>
 *   <li>{@code CommentService.publishCommented}（评论 UNDER_REVIEW → VISIBLE）。</li>
 * </ol>
 * ⚠️ 挂起分支<b>刻意不发</b>：审核没过就通知，被 @ 的人点进去是 404
 * （与既有 {@code ContentCommentedEvent} 的口径逐字一致 —— 同一个方法里发的）。
 *
 * <h2>🔴 非 PUBLIC 的内容不发（Story 3.2 留下的硬约束）</h2>
 * PRIVATE（同步开关关掉的 Diary）照样落 @ 名单 —— 作者自己那条时间线上的「@昵称」
 * 要能高亮能点（Story 3.3）。但可见范围创建后不可更改（FR-83 AC7），
 * 被 @ 的人<b>永远打不开它</b>，通知发出去就是一条点进去是空态的骚扰。
 * 判据写在<b>发布侧</b>（不发这个事件），notify 侧因此不必再判一次可见范围。
 *
 * <h2>为什么不复用 {@code ContentPublishedEvent} / {@code ContentCommentedEvent}</h2>
 * 那两个事件的消费者（里程碑、互动通知）与本事件的收件人集合完全不同：
 * 它们推给<b>作者</b>，本事件推给<b>被 @ 的人</b>。往那两个记录上挂一个
 * {@code mentionedUserIds} 会让每个既有消费者都得判一次"这个字段跟我有关吗"。
 *
 * @param postId            内容 id（帖子提及与评论提及都落在这条内容的详情页）
 * @param commentId         评论 id；<b>为 null = 正文提及</b>，非空 = 评论提及
 * @param actorId           @ 别人的那个人（作者 / 评论者）
 * @param contentAuthorId   内容作者 id —— notify 侧判 R2 抑制要用（见 {@code MentionNotifyListener}）
 * @param mentionedUserIds  被 @ 的人，已由 {@code MentionSanitizer} 洗过（≤5、无自己、无注销、无拉黑）
 * @param createdAt         事件时刻（UTC）
 */
public record ContentMentionedEvent(
        long postId,
        Long commentId,
        long actorId,
        long contentAuthorId,
        List<Long> mentionedUserIds,
        Instant createdAt) {

    /** 正文提及还是评论提及 —— 决定文案与深链是否锚定评论区（AC3/AC4）。 */
    public boolean isComment() {
        return commentId != null;
    }
}

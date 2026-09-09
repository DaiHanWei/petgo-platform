package com.tailtopia.admin.warmreply.dto;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import java.time.Instant;
import java.util.List;

/** A9 暖贴回复跟进工作台的视图模型（V1.3.0 Story 4.4）。只读投影，不含评论正文以外的用户数据。 */
public final class WarmReplyViews {

    private WarmReplyViews() {
    }

    /**
     * 左栏一行（AC2）：① 虚拟账号昵称 +「收到回复」+ 状态点；② 帖 #postId · 真实用户昵称 回复了暖评 + 相对时间；多条 → 「+N 条新回复」。
     * 已跟进页签：处置徽标 + 操作人 + 时间。
     */
    public record Row(long id, String virtualNickname, long postId, String replierNickname, int pendingReplyCount,
            Instant lastReplyAt, FollowupStatus status, HandledAction handledAction, String handledByName, Instant handledAt) {

        public boolean pending() {
            return status == FollowupStatus.PENDING;
        }
    }

    /** 线程里的一条评论：{@code fresh} = 入队后新收到的真实用户回复（高亮）；{@code underReview} = 审核中（运营刚发的回复）。 */
    public record ThreadComment(long id, String authorName, boolean virtual, String body, Instant createdAt, boolean fresh,
            boolean underReview) {
    }

    /**
     * 右栏（AC3 / AC6）：帖子卡 + 线程（虚拟一级 + 其下可见 / 审核中二级，时间正序）+ 操作区数据。
     * {@code contentDeleted} = 帖子或虚拟评论已删 → 快照占位「内容已删除」，只允许标记已读。
     */
    public record Detail(long id, FollowupStatus status, HandledAction handledAction, String handledByName, Instant handledAt,
            long postId, String postSummary, String postAuthorName, long visibleCount, boolean contentDeleted,
            long virtualUserId, String virtualNickname, ThreadComment virtualComment, List<ThreadComment> replies,
            int pendingReplyCount, Instant lastReplyAt, String idempotencyKey, Long justRepliedId) {

        public boolean pending() {
            return status == FollowupStatus.PENDING;
        }
    }
}

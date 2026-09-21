package com.tailtopia.admin.warmreply;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.admin.warmreply.domain.FollowupStatus;
import com.tailtopia.admin.warmreply.domain.HandledAction;
import com.tailtopia.admin.warmreply.domain.WarmReplyFollowup;
import com.tailtopia.admin.warmreply.repository.WarmReplyFollowupRepository;
import com.tailtopia.admin.warmreply.service.WarmReplyQueueService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.CommentResponse;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.CommentService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（真库）：暖贴回复跟进入队 / 出队端到端（V1.3.0 Story 4.3 AC2 / AC3 / AC4 / AC6）。
 * 造数：真实作者帖 → 虚拟账号一级评论（直接 {@code Comment.create} 造 VISIBLE，绕过审核只为测试）→ 真实用户 {@code createReply}
 * → 等异步机审（stub PASS）转可见触发 {@code ContentCommentedEvent} → 断言入队；再回复 → 合并累加；{@code delete} → 减一 / 归零删行；
 * 虚拟回虚拟 / 真实一级被回 / 虚拟二级被回 → 不入队；HANDLED 项不受出队影响。
 * 监听器是 AFTER_COMMIT：这里每一步都不在外层事务里跑（测试方法无 @Transactional），事件在各自 service 事务提交后触发。
 */
class WarmReplyEnqueueIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ContentService contentService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private WarmReplyFollowupRepository followups;
    @Autowired
    private WarmReplyQueueService queue;

    private long post(User author) {
        return contentService.publish(author.getId(), new ContentPostCreateRequest(ContentType.DAILY, null, "冷帖 " + UUID.randomUUID(), null),
                UUID.randomUUID().toString()).id();
    }

    private User virtual() {
        return users.save(User.newVirtual("virtual:" + UUID.randomUUID(), "Meow" + SEQ.incrementAndGet(), null, 1L));
    }

    /**
     * 回复某条一级评论并等它经异步机审（stub 恒 PASS → {@code approveComment} → VISIBLE → AFTER_COMMIT 发 ContentCommentedEvent）转可见。
     * 不能手动再调 {@code approveComment}：与 {@code CommentModerationListener} 的 @Async 线程竞态会双发事件（复审 #2），
     * 轮询 + 短等待与 {@code InteractionNotifySuppressionIntegrationTest.awaitProcessed} 同款。
     */
    private long replyAndAwaitVisible(long parentId, User replier, String body) throws Exception {
        CommentResponse r = commentService.createReply(parentId, replier.getId(), body);
        for (int i = 0; i < 100; i++) {
            Comment c = comments.findById(r.id()).orElseThrow();
            if (c.getModerationStatus() == CommentModerationStatus.VISIBLE) {
                Thread.sleep(150); // 让 AFTER_COMMIT 的入队监听器（REQUIRES_NEW）跑完
                return r.id();
            }
            Thread.sleep(30);
        }
        throw new AssertionError("评论 " + r.id() + " 迟迟没走完机审");
    }

    /** 出队监听同为 AFTER_COMMIT：删 / 下架后等一小会再断言。 */
    private static void settle() throws Exception {
        Thread.sleep(150);
    }

    private Optional<WarmReplyFollowup> pending(long virtualCommentId) {
        return followups.findByVirtualCommentIdAndStatus(virtualCommentId, FollowupStatus.PENDING);
    }

    @Test
    void enqueueMergeAndDequeue() throws Exception {
        User author = newUser();
        User real1 = newUser();
        User real2 = newUser();
        User v = virtual();
        long postId = post(author);
        Comment warm = comments.save(Comment.create(postId, null, v.getId(), "暖评"));
        long before = queue.pendingCount();

        // 入队：真实用户回复虚拟一级评论
        long r1 = replyAndAwaitVisible(warm.getId(), real1, "谢谢回复！");
        WarmReplyFollowup f = pending(warm.getId()).orElseThrow();
        assertThat(f.getPendingReplyCount()).isEqualTo(1);
        assertThat(f.getPostId()).isEqualTo(postId);
        assertThat(f.getVirtualUserId()).isEqualTo(v.getId());
        assertThat(f.getLastReplyId()).isEqualTo(r1);
        assertThat(queue.pendingCount()).isEqualTo(before + 1);

        // 合并累加：第二条真实回复 → 同一项 +1，last_reply 更新
        long r2 = replyAndAwaitVisible(warm.getId(), real2, "我也想问");
        f = pending(warm.getId()).orElseThrow();
        assertThat(f.getPendingReplyCount()).isEqualTo(2);
        assertThat(f.getLastReplyId()).isEqualTo(r2);
        assertThat(followups.findAll().stream().filter(x -> x.getVirtualCommentId().equals(warm.getId())).count()).isEqualTo(1);

        // 出队：作者自删一条 → 减一；再删 → 归零删行
        commentService.delete(r2, real2.getId());
        settle();
        assertThat(pending(warm.getId()).orElseThrow().getPendingReplyCount()).isEqualTo(1);
        commentService.delete(r1, real1.getId());
        settle();
        assertThat(pending(warm.getId())).isEmpty();
        assertThat(queue.pendingCount()).isEqualTo(before);
    }

    @Test
    void adminTakedownDequeuesButHandledItemsAreUntouched() throws Exception {
        User author = newUser();
        User real = newUser();
        User v = virtual();
        long postId = post(author);
        Comment warm = comments.save(Comment.create(postId, null, v.getId(), "暖评"));
        long reply = replyAndAwaitVisible(warm.getId(), real, "回复");
        WarmReplyFollowup f = pending(warm.getId()).orElseThrow();

        // 运营标记已读 → HANDLED；之后回复被下架不影响历史项
        queue.markRead(f.getId(), 1L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queue.markRead(f.getId(), 1L))
                .isInstanceOf(com.tailtopia.shared.error.AppException.class);
        commentService.takedownComment(reply);
        settle();
        WarmReplyFollowup handled = followups.findById(f.getId()).orElseThrow();
        assertThat(handled.getStatus()).isEqualTo(FollowupStatus.HANDLED);
        assertThat(handled.getHandledAction()).isEqualTo(HandledAction.READ);
        assertThat(handled.getPendingReplyCount()).isEqualTo(1);

        // 新的真实回复 → 新的 PENDING 项（部分唯一索引只约束 PENDING）；运营下架它 → 归零删行
        long reply2 = replyAndAwaitVisible(warm.getId(), real, "再回复");
        assertThat(pending(warm.getId())).isPresent();
        commentService.takedownComment(reply2);
        settle();
        assertThat(pending(warm.getId())).isEmpty();
        assertThat(followups.findById(f.getId())).isPresent();
    }

    @Test
    void removingUnderReviewOrVirtualReplyDoesNotDecrement() throws Exception {
        // 复审 #1：审核中的回复被作者删 / 虚拟号回虚拟号的回复被删 → 当初没入队，不能减
        User author = newUser();
        User real = newUser();
        User v = virtual();
        User v2 = virtual();
        long postId = post(author);
        Comment warm = comments.save(Comment.create(postId, null, v.getId(), "暖评"));
        replyAndAwaitVisible(warm.getId(), real, "回复");
        assertThat(pending(warm.getId()).orElseThrow().getPendingReplyCount()).isEqualTo(1);

        Comment underReview = comments.save(Comment.createUnderReview(postId, warm.getId(), real.getId(), "审核中"));
        commentService.delete(underReview.getId(), real.getId());
        settle();
        assertThat(pending(warm.getId()).orElseThrow().getPendingReplyCount()).isEqualTo(1);

        long vReply = replyAndAwaitVisible(warm.getId(), v2, "虚拟回虚拟");
        commentService.delete(vReply, v2.getId());
        settle();
        assertThat(pending(warm.getId()).orElseThrow().getPendingReplyCount()).isEqualTo(1);
    }

    @Test
    void takingDownVirtualTopLevelDropsPendingItem() throws Exception {
        // 复审 #3：虚拟一级评论本身被下架 → PENDING 项整条删除（否则角标孤儿、4-4 回复 404）
        User author = newUser();
        User real = newUser();
        User v = virtual();
        long postId = post(author);
        Comment warm = comments.save(Comment.create(postId, null, v.getId(), "暖评"));
        replyAndAwaitVisible(warm.getId(), real, "回复");
        assertThat(pending(warm.getId())).isPresent();
        commentService.takedownComment(warm.getId());
        settle();
        assertThat(pending(warm.getId())).isEmpty();
    }

    @Test
    void notEnqueuedForVirtualReplierRealParentOrSecondLevelVirtual() throws Exception {
        User author = newUser();
        User real = newUser();
        User v1 = virtual();
        User v2 = virtual();
        long postId = post(author);
        Comment warm = comments.save(Comment.create(postId, null, v1.getId(), "暖评"));
        Comment realTop = comments.save(Comment.create(postId, null, real.getId(), "真实一级"));

        // 虚拟账号回虚拟一级 → 不入队
        replyAndAwaitVisible(warm.getId(), v2, "虚拟回虚拟");
        assertThat(pending(warm.getId())).isEmpty();
        // 真实一级被真实回复 → 不入队
        replyAndAwaitVisible(realTop.getId(), real, "真实回真实");
        assertThat(pending(realTop.getId())).isEmpty();
        // D-35：虚拟账号发的二级（挂在真实一级下）被真实用户回复 → 归并到一级，parentAuthorId 是真实作者 → 不入队
        Comment virtualSecond = comments.save(Comment.create(postId, realTop.getId(), v1.getId(), "虚拟二级"));
        replyAndAwaitVisible(virtualSecond.getId(), real, "回虚拟二级");
        assertThat(pending(realTop.getId())).isEmpty();
        assertThat(pending(virtualSecond.getId())).isEmpty();
    }
}

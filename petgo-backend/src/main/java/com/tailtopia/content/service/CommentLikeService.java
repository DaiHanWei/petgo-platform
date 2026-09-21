package com.tailtopia.content.service;

import com.tailtopia.content.domain.Comment;
import com.tailtopia.content.repository.CommentLikeRepository;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评论点赞（V1.3.0 批次 A · Story 2.4 · AD-A7）。
 *
 * <p>🔴 <b>幂等靠唯一约束兜底，不靠「先查后插」</b>（AC3）：`exists → save` 之间有并发窗口，
 * 两个请求同时进来都会查到「没赞过」。这里的做法是 <b>INSERT … ON CONFLICT DO NOTHING</b>
 * —— 撞约束不是错误，结果对用户完全一样，且没有窗口。
 * ⚠️ 不要改回「save 后 catch 约束异常」：异常已让事务 rollback-only，提交时仍会 500。
 *
 * <p>点赞数<b>不在这里返回</b>：它是实时聚合值，由读路径（{@code CommentQueryService}）
 * 批量取。写路径回一个数就得再查一次，而那个数在客户端拿到时又可能变了。
 */
@Service
public class CommentLikeService {

    private final CommentRepository comments;
    private final CommentLikeRepository likes;

    public CommentLikeService(CommentRepository comments, CommentLikeRepository likes) {
        this.comments = comments;
        this.likes = likes;
    }

    /**
     * 点赞一条评论。幂等：已赞过再点仍然成功，不产生第二行。
     *
     * <p>软删的评论不可点赞（AC9：它不该出现在任何列表里，能被点到说明客户端拿着过期数据）。
     */
    @Transactional
    public void like(long commentId, long userId) {
        requireAliveComment(commentId);
        // 并发双击：uq_comment_likes_comment_user 兜底 → 返回 0，视为已赞，不报错。
        likes.insertIfAbsent(commentId, userId);
    }

    /** 取消点赞。没赞过也算成功（幂等，客户端不必先查状态）。 */
    @Transactional
    public void unlike(long commentId, long userId) {
        likes.deleteByCommentIdAndUserId(commentId, userId);
    }

    /**
     * 评论必须存在且未软删。
     *
     * <p>⚠️ 这里**不判可见性**（R1/R2 隐藏关系、审核态）：那是读路径的事。
     * 能点到这条评论说明它已经渲染在某人屏幕上了，此时因为可见性规则把点赞拒掉，
     * 用户只会看到一个点不动的心 —— 而可见性本身是无感知设计（AC3 无感知）。
     */
    private void requireAliveComment(long commentId) {
        Comment c = comments.findById(commentId)
                .filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> AppException.notFound("评论不存在或已删除"));
        if (c.getId() == null) {
            throw AppException.notFound("评论不存在或已删除");
        }
    }
}

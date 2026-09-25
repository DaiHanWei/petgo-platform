package com.tailtopia.place.service;

import com.tailtopia.content.domain.CommentModerationStatus;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.place.domain.Place;
import com.tailtopia.place.domain.PlaceComment;
import com.tailtopia.place.dto.PlaceCommentCreateRequest;
import com.tailtopia.place.event.PlaceCommentSubmittedEvent;
import com.tailtopia.place.repository.PlaceCommentRepository;
import com.tailtopia.place.repository.PlaceRepository;
import com.tailtopia.shared.error.AppException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场所评论写入 / 删除（V1.3.0 batch-b1 Story 1.7 · FR-112.3 · AD-8）。
 *
 * <h2>审核：与内容评论**同一套**三方审核，状态**各存各的**（AC5 · AD-8 §3）</h2>
 * 逐条对齐 {@code content.service.CommentService} 的既定范式：
 * <ol>
 *   <li><b>L1 硬黑名单同步即时 422</b>（{@link ContentModerationService#isL1Blocked(String)}）——
 *       本地词库、无网络、毫秒级。明显违规词秒拒、**不落库**；</li>
 *   <li>否则落 {@code UNDER_REVIEW} 秒回（作者自己立刻看得见，他人不可见），
 *       阿里云评分交 {@code @Async} 监听（先发后审）；</li>
 *   <li>评分 PASS → VISIBLE；高危 / 三方降级 / 审核异常 → <b>留在 UNDER_REVIEW</b>
 *       （fail-closed，**绝不自动放行**）。</li>
 * </ol>
 * ⚠️ 第 ③ 步与内容评论有一处**有意的差别**，见 {@code PlaceCommentModerationListener} 的类注释：
 * 内容评论那条会 {@code enqueueComment} 进**运营人工队列**，而那个队列的 {@code content_type}
 * 只有 {@code CONTENT_POST}/{@code COMMENT} 两个值、且 admin 侧的处置动作是直接打
 * {@code CommentService}（也就是 {@code comments} 表）。把场所评论的 id 塞进去会让运营
 * "通过"一条**别的表里 id 相同的内容评论** —— 那是数据事故，不是小瑕疵。
 * 接入运营队列需要 admin 主题扩 {@code ReviewContentType}，已记入 story 的待办。
 *
 * <h2>🔴 没有"回复"，也永远不会有</h2>
 * 场所评论只有一级（PRD ③ / AC2）。本类没有 {@code createReply}，实体也没有 {@code parentId}。
 */
@Service
public class PlaceCommentService {

    /** L1 命中文案（仅后端日志/排查；前端按 error type 映射单一 toast，不展示原文）。 */
    static final String L1_BLOCKED_MESSAGE = "内容包含不当词汇，请修改后重试";

    private final PlaceCommentRepository comments;
    private final PlaceRepository places;
    private final ContentModerationService moderation;
    private final ApplicationEventPublisher events;
    private final PlaceAttitudeCounters counters;

    public PlaceCommentService(PlaceCommentRepository comments, PlaceRepository places,
            ContentModerationService moderation, ApplicationEventPublisher events,
            PlaceAttitudeCounters counters) {
        this.comments = comments;
        this.places = places;
        this.moderation = moderation;
        this.events = events;
        this.counters = counters;
    }

    /**
     * 发表一条场所评论（AC2/AC3/AC5）。
     *
     * @throws AppException 场所不存在 / 已下架 → 404（与详情同口径）；命中 L1 → 422
     */
    @Transactional
    public PlaceComment create(String placeToken, long authorId, PlaceCommentCreateRequest req) {
        Place place = requireActive(placeToken);
        String body = req.body() == null ? "" : req.body().trim();
        if (body.isEmpty()) {
            throw AppException.validation("评论内容不能为空");
        }
        if (moderation.isL1Blocked(body)) {
            throw AppException.commentBlocked(L1_BLOCKED_MESSAGE);
        }
        PlaceComment saved = comments.save(
                PlaceComment.createUnderReview(place.getId(), authorId, body, req.attitude()));
        events.publishEvent(new PlaceCommentSubmittedEvent(
                saved.getId(), body, saved.getContentVersion()));
        // 🔴 **这里不加计数**（Story 1.8 · AC5 触发点 ① 的落点在 approve 那一步）：
        // 先发后审之下，此刻这条评论还是 UNDER_REVIEW、对他人不可见 ——
        // 创建即 +1 会让一条尚未过审、甚至最终被拒的评论立刻出现在所有人看到的公开计数里。
        // 计数只认 VISIBLE，与 DB 回算口径逐字一致（见 PlaceAttitudeCounters 的类注释）。
        return saved;
    }

    /**
     * 用户自删自己的评论（AC7）。
     *
     * <p>🔒 **只允许评论作者本人**。与内容评论**有意不同**：那边还允许「内容作者」删别人的评论
     * （他的地盘），而**场所没有主人** —— 标记人既不能编辑也不能删除场所本身（2026-09-15 拍板），
     * 给他删别人评论的权力等于凭空发明一种对公共条目的管理权。运营删评论走后台 AB-17A。
     *
     * @throws AppException 评论不存在 / 已删 → 404；不是本人 → 403
     */
    @Transactional
    public void deleteOwn(long commentId, long userId) {
        PlaceComment c = comments.findByIdAndDeletedAtIsNull(commentId)
                .orElseThrow(() -> AppException.notFound("评论不存在"));
        // ⚠️ 先查存在、再判权限，两步都必须在服务端：客户端的 `mine` 标记只决定"要不要画删除按钮"。
        if (c.getAuthorId() == null || c.getAuthorId() != userId) {
            throw AppException.forbidden("无权删除该评论");
        }
        // 🔴 计数只减"此前确实计入过"的那些（Story 1.8 · AC5 触发点 ②）：
        // 一条还挂在 UNDER_REVIEW 的评论被删时不该减 —— 它从来没被加进去过，
        // 减了会把这个场所的数字一直压低，直到下一次自愈。
        boolean wasCounted = c.getModerationStatus() == CommentModerationStatus.VISIBLE;
        c.softDelete();
        comments.save(c);
        if (wasCounted) {
            counters.onVisibleCommentRemoved(c.getPlaceId(), c.getAttitude());
        }
    }

    /**
     * 运营下架一条评论（Story 1.8 · AC5 触发点 ③）。仅 VISIBLE 可下架。
     *
     * <p>⚠️ 本 story **不提供 admin 端点** —— 后台处置走 AB-17A（admin 主题）。
     * 这个方法存在的理由是：计数的三个触发点必须**在同一层**收口，
     * 否则 admin 那侧接上时很容易直接改仓储、把计数绕过去。
     *
     * @return 是否真的发生了下架（幂等：已下架 / 非 VISIBLE → false）
     */
    @Transactional
    public boolean takedown(long commentId) {
        return comments.findByIdAndDeletedAtIsNull(commentId)
                .filter(PlaceComment::takedown)
                .map(c -> {
                    comments.save(c);
                    counters.onVisibleCommentRemoved(c.getPlaceId(), c.getAttitude());
                    return true;
                })
                .orElse(false);
    }

    /**
     * 注销级联（NFR-8 / D1/D2，安全攸关）：把该用户的场所评论置 {@code AUTHOR_DEACTIVATED}，
     * 从此对**他人**不可见。
     *
     * <p>🔴 **必须在 users 行被删/匿名化之前调用** —— 那之后 {@code author_id} 就认不出人了
     * （与 content 侧 {@code deactivateAuthorContent} 同一约束，编排见 {@code AccountDeletionService}）。
     *
     * <p>⚠️ 这不是删除：评论内容是场所攻略的一部分，不随人消失；消失的是那个人的身份展示。
     * 幂等可重跑（注销作业失败会重扫续跑）。
     *
     * @return 实际改动的条数（供审计日志，**不记任何评论内容**）
     */
    @Transactional
    public int deactivateAuthorComments(long userId) {
        int changed = 0;
        for (PlaceComment c : comments.findByAuthorIdAndDeletedAtIsNull(userId)) {
            // 注销前它是否计入过公开计数 —— 之后要把它从计数里拿掉（它对他人已不可见）。
            boolean wasCounted = c.getModerationStatus() == CommentModerationStatus.VISIBLE;
            if (c.deactivateAuthor()) {
                comments.save(c);
                if (wasCounted) {
                    counters.onVisibleCommentRemoved(c.getPlaceId(), c.getAttitude());
                }
                changed++;
            }
        }
        return changed;
    }

    /**
     * 异步审核：通过 → VISIBLE。幂等（非挂起态 no-op）。
     *
     * <p>这里也是 **Story 1.8 计数触发点 ①** 的真正落点：评论转为可见的那一刻才 +1。
     * 幂等保证了它不会重复加 —— {@code approveModeration()} 只在 UNDER_REVIEW → VISIBLE
     * 时返回 true。
     */
    @Transactional
    public void approve(long commentId) {
        comments.findByIdAndDeletedAtIsNull(commentId).ifPresent(c -> {
            if (c.approveModeration()) {
                comments.save(c);
                counters.onCommentBecameVisible(c.getPlaceId(), c.getAttitude());
            }
        });
    }

    private Place requireActive(String token) {
        return places.resolveForView(token)
                .orElseThrow(() -> AppException.notFound("场所不存在"));
    }
}

package com.tailtopia.place.service;

import com.tailtopia.content.service.CommentVerdict;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.place.event.PlaceCommentSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 场所评论异步审核（V1.3.0 batch-b1 Story 1.7 · AC5）。
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} —— 与内容评论同一范式、同一个评分器
 * （{@link ContentModerationService#moderateComment(String)}），**状态写各自的表**（AD-8 §3）。
 *
 * <ul>
 *   <li>{@code PASS} → {@link PlaceCommentService#approve(long)}：转 VISIBLE，对外可见。</li>
 *   <li>{@code HIGH_RISK} / {@code DEGRADED} / 审核异常 → <b>留在 UNDER_REVIEW</b>
 *       （仅作者可见），**绝不自动放行**。</li>
 * </ul>
 *
 * <h2>🔴 降级分支为什么**不入运营人工队列**（与内容评论的唯一差别，有意为之）</h2>
 * 内容评论走 {@code ManualReviewGate.enqueueComment(commentId, …)}，而那张队列表的
 * {@code content_type} 只有 {@code CONTENT_POST} / {@code COMMENT} 两个值，admin 侧对
 * {@code COMMENT} 行的处置动作是直接打 {@code CommentService}（也就是 {@code comments} 表）。
 * 把 {@code place_comments.id} 塞进去，运营点一次"通过"就会去**放行 comments 表里同号的那条
 * 别人的评论** —— 那是数据事故，不是小瑕疵。
 * <p>接入运营队列需要 admin 主题扩 {@code ReviewContentType}（+ 迁移 + 处置路由），
 * 不在本主题范围内，已记入 story 的待办。在那之前的行为是：
 * <b>降级评论一直挂在 UNDER_REVIEW（他人不可见、作者可见）</b> ——
 * 宁可挂着，也不自动放行（安全侧守住），更不自动判 REJECTED（那是终态，等于让一次三方超时
 * 判死一条可能完全正常的评论）。
 *
 * <p>独立 bean（非 service 内自调）确保 {@code @Async} 代理生效。
 */
@Component
public class PlaceCommentModerationListener {

    private static final Logger log = LoggerFactory.getLogger(PlaceCommentModerationListener.class);

    private final ContentModerationService moderation;
    private final PlaceCommentService placeComments;

    public PlaceCommentModerationListener(ContentModerationService moderation,
            PlaceCommentService placeComments) {
        this.moderation = moderation;
        this.placeComments = placeComments;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlaceCommentSubmitted(PlaceCommentSubmittedEvent event) {
        CommentVerdict verdict;
        try {
            verdict = moderation.moderateComment(event.body());
        } catch (RuntimeException ex) {
            // fail-closed：审核异常 → 留在 UNDER_REVIEW。只记异常类型，**绝不记评论原文**。
            log.warn("场所评论审核异常，fail-closed 保持挂起 placeCommentId={}：{}",
                    event.placeCommentId(), ex.getClass().getSimpleName());
            return;
        }
        if (verdict == CommentVerdict.PASS) {
            placeComments.approve(event.placeCommentId());
            return;
        }
        // HIGH_RISK / DEGRADED（及理论不可达的 L1_BLOCKED —— 创建时已同步拦掉）：
        // 什么都不做 = 保持 UNDER_REVIEW。这不是"忘了处理"，理由见类注释。
        log.info("场所评论未通过自动审核，保持挂起 placeCommentId={} verdict={}",
                event.placeCommentId(), verdict);
    }
}

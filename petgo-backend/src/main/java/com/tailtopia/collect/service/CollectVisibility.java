package com.tailtopia.collect.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 帖子「此刻对某用户是否可见」（V1.4.0 ④ Story 2.1 · P-5 / P-7 · AD-11 不可见口径）。
 *
 * <p>两处共用、同一份规则：① 收藏 / 取消时决定发不发 {@code ContentCollectedEvent} / {@code ContentUncollectedEvent}
 * （两个事件同一门槛：收藏那一刻若已不可见就不发 Collected，免得之后取消按 P-7 判不可见、不发 Uncollected 而白得分；
 * 收藏后才变不可见再取消 = AC11c「移除不可见条目不扣」，按 PRD 不扣回；未配对的 Uncollected 由 ① 按「原流水不存在则不扣」吸收）；
 * ② 我的收藏列表（Story 2.2）判条目可不可见。
 *
 * <p>规则（全满足才可见）：未软删；{@code PUBLISHED}，或作者本人看自己的 {@code UNDER_REVIEW}
 * （{@code AUTHOR_DEACTIVATED} 一律不可见）；viewer 没举报过该帖；作者 ≠ viewer 时双方任一方向无隐藏关系
 * （{@link UserHideRelationReader#hiddenEitherWay}，拉黑与举报隐藏都算）。<b>不看 {@code visibility}</b>
 * （私密帖由收藏入口的 422 挡住）。
 *
 * <p>与详情 {@code requireVisiblePost} 的差别是刻意的：详情只判单向（作者拉黑我时详情仍打得开，不暴露被拉黑），
 * 这里判双向。
 */
@Component
public class CollectVisibility {

    private final ReportService reports;
    private final UserHideRelationReader hideRelations;

    public CollectVisibility(ReportService reports, UserHideRelationReader hideRelations) {
        this.reports = reports;
        this.hideRelations = hideRelations;
    }

    /** 批量：posts 中对 viewer 可见的 postId 集合。固定至多 2 次查询（举报 1 次 + 双向隐藏 1 次），与条数无关。 */
    public Set<Long> visiblePostIds(long viewerId, Collection<ContentPost> posts) {
        if (posts == null || posts.isEmpty()) {
            return Set.of();
        }
        // 先在内存里按软删 / 状态过滤，不可见的不浪费查询。
        List<ContentPost> candidates = posts.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED
                        || (p.getStatus() == PostStatus.UNDER_REVIEW && viewerId == p.getAuthorId()))
                .toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Set<Long> reported = reports.reportedPostIds(viewerId,
                candidates.stream().map(ContentPost::getId).toList());
        Set<Long> others = candidates.stream()
                .map(ContentPost::getAuthorId)
                .filter(a -> a != viewerId)
                .collect(Collectors.toSet());
        Set<Long> hiddenAuthors = others.isEmpty() ? Set.of() : hideRelations.hiddenEitherWay(viewerId, others);
        Set<Long> out = new HashSet<>();
        for (ContentPost p : candidates) {
            if (reported.contains(p.getId())) {
                continue;
            }
            if (p.getAuthorId() != viewerId && hiddenAuthors.contains(p.getAuthorId())) {
                continue;
            }
            out.add(p.getId());
        }
        return out;
    }

    /** 单条：等价于 {@code visiblePostIds(viewerId, List.of(post)).contains(post.getId())}。 */
    public boolean isVisibleTo(long viewerId, ContentPost post) {
        return post != null && visiblePostIds(viewerId, List.of(post)).contains(post.getId());
    }
}

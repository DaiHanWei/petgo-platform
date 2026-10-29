package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentLike;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.paging.KeysetCursor;
import com.tailtopia.social.dto.FollowListPage;
import com.tailtopia.social.service.FollowListQueryService;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点赞人列表（V1.3.4）。可见性与帖子详情共用 {@link ContentDetailService#requireVisiblePost}；
 * 私密帖对非作者额外 404。过滤（已注销 / 非 ACTIVE / 我拉黑的人）在 SQL 里做，翻页不会出现“满页被滤空”。
 * 行组装复用关注列表的 {@link FollowListQueryService#itemsFor}。
 */
@Service
public class PostLikersService {

    static final int PAGE_SIZE = 20;

    private final ContentDetailService detail;
    private final ContentLikeRepository likes;
    private final FollowListQueryService followLists;

    public PostLikersService(ContentDetailService detail, ContentLikeRepository likes,
            FollowListQueryService followLists) {
        this.detail = detail;
        this.likes = likes;
        this.followLists = followLists;
    }

    @Transactional(readOnly = true)
    public FollowListPage likers(long postId, long viewerId, String cursor) {
        ContentPost post = detail.requireVisiblePost(postId, viewerId);
        // 详情页本身不拦私密帖（他人拿不到入口）；点赞人列表按 spec 对非作者一律 404。
        if (post.getVisibility() == ContentVisibility.PRIVATE && !post.getAuthorId().equals(viewerId)) {
            throw AppException.notFound(ContentDetailService.GONE_DETAIL);
        }
        KeysetCursor c = KeysetCursor.decodeOrNull(cursor);
        if (c == null) {
            c = KeysetCursor.firstPage();
        }
        List<ContentLike> rows = likes.findLikersPage(postId, viewerId, c.createdAt(), c.id(),
                PageRequest.of(0, PAGE_SIZE + 1));
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ContentLike> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        if (page.isEmpty()) {
            return new FollowListPage(List.of(), null, false);
        }
        var items = followLists.itemsFor(page.stream().map(ContentLike::getUserId).toList(), viewerId);
        ContentLike last = page.get(page.size() - 1);
        String next = hasMore ? new KeysetCursor(last.getCreatedAt(), last.getId()).encode() : null;
        return new FollowListPage(items, next, hasMore);
    }
}

package com.petgo.content.service;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.domain.ContentPost;
import com.petgo.content.domain.ContentType;
import com.petgo.content.domain.FeedCategory;
import com.petgo.content.dto.FeedItemResponse;
import com.petgo.content.dto.FeedPageResponse;
import com.petgo.content.repository.ContentLikeRepository;
import com.petgo.content.repository.ContentLikeRepository.PostLikeCount;
import com.petgo.content.repository.ContentPostRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feed 读取服务（Story 3.2）。时间倒序 + 宠物状态硬过滤 + 分类过滤 + 游标分页，**无算法、无关注、无缓存**。
 *
 * <p>硬过滤语义（FR-17，后端权威）：
 * <ul>
 *   <li>A / C / 游客 → 三类全显（无 type 过滤）。</li>
 *   <li>B（计划养）→ 不显成长日历快乐时刻（{@code type != GROWTH_MOMENT}）。</li>
 * </ul>
 * 作者昵称/头像经 {@link AccountQueryService} 取（**不直 join users 表**），注销作者匿名化（NFR-8）。
 */
@Service
public class FeedService {

    /** Feed 每批条数（FR-17）。 */
    public static final int PAGE_SIZE = 20;
    private static final String STATUS_PLAN_TO_ADOPT = "B";

    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final ContentLikeRepository likes;

    public FeedService(ContentPostRepository posts, AccountQueryService accountQueryService,
            ContentLikeRepository likes) {
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.likes = likes;
    }

    /** 一页帖子的点赞数（PRD-642 卡片点赞数）：一次 GROUP BY 批量取，无赞的帖默认 0。 */
    private Map<Long, Long> likeCounts(List<ContentPost> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = page.stream().map(ContentPost::getId).toList();
        return likes.countByPostIdIn(ids).stream()
                .collect(Collectors.toMap(PostLikeCount::getPostId, PostLikeCount::getLikeCount));
    }

    /**
     * 读取一批 Feed。
     *
     * @param petStatus 调用者宠物状态（A/B/C）；null = 游客（视作全显）
     * @param category  分类 Tab（ALL/DAILY/GROWTH_MOMENT/KNOWLEDGE）
     * @param cursor    上一批末尾游标 token；null = 首批
     */
    @Transactional(readOnly = true)
    public FeedPageResponse loadFeed(String petStatus, String category, String cursor) {
        boolean excludeGrowth = STATUS_PLAN_TO_ADOPT.equals(petStatus);
        FeedCategory cat = FeedCategory.parse(category);
        ContentType type = cat.toContentType();
        boolean requirePet = cat.requiresPet();

        FeedCursor decoded = (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);

        // 多取一条以判定 hasMore（不漏不重）。
        List<ContentPost> rows = posts.findFeed(
                excludeGrowth, type, requirePet,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ContentPost> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(
                page.stream().map(ContentPost::getAuthorId).toList());
        Map<Long, Long> likeCounts = likeCounts(page);

        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L)))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new FeedPageResponse(items, nextCursor, hasMore);
    }

    /**
     * 「我的发布」（Story 7.1，FR-36）：当前用户未软删的三类混合内容，时间倒序游标分页。
     * 经本 service 接口供 me 端点调用（禁 profile/auth 直 join content repository）。
     */
    @Transactional(readOnly = true)
    public FeedPageResponse myPosts(long userId, String cursor) {
        FeedCursor decoded = (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);
        List<ContentPost> rows = posts.findMyPosts(
                userId,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ContentPost> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(
                page.stream().map(ContentPost::getAuthorId).toList());
        Map<Long, Long> likeCounts = likeCounts(page);
        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L)))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new FeedPageResponse(items, nextCursor, hasMore);
    }
}

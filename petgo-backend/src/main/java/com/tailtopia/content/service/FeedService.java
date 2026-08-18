package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.FeedCategory;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.CommentRepository.PostCommentCount;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentLikeRepository.PostLikeCount;
import com.tailtopia.content.repository.ContentPostRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final ContentLikeRepository likes;

    /** V1.1.6 Story 3.1：评论数批量聚合。⚠️ 只用批量方法，逐条那个是详情页的。 */
    private final CommentRepository comments;

    public FeedService(ContentPostRepository posts, AccountQueryService accountQueryService,
            ContentLikeRepository likes, CommentRepository comments) {
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.likes = likes;
        this.comments = comments;
    }

    /**
     * 一页帖子里<b>当前访客赞过哪些</b>（V1.1.6 Story 3.1 · AD-7 Rule 2）。
     *
     * <p>🛡 <b>未登录访客整批短路，不发这次查询</b> —— Feed 对游客开放，
     * 而游客不可能赞过任何东西，白跑一次查询没有意义。
     */
    private Set<Long> likedIds(List<ContentPost> page, Long viewerId) {
        if (viewerId == null || page.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = page.stream().map(ContentPost::getId).toList();
        return Set.copyOf(likes.findLikedPostIds(viewerId, ids));
    }

    /**
     * 一页帖子各自的评论数（V1.1.6 Story 3.1 · AD-7 Rule 2）。
     *
     * <p>🔴 口径与内容详情页<b>逐字一致</b>（含访客自己那条尚未对外可见的评论）——
     * 两处是同一个数字，不一致用户只会以为出 bug。
     */
    private Map<Long, Long> commentCounts(List<ContentPost> page, Long viewerId) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = page.stream().map(ContentPost::getId).toList();
        return comments.countVisibleForViewerIn(ids, viewerId).stream()
                .collect(Collectors.toMap(PostCommentCount::getPostId,
                        PostCommentCount::getCommentCount));
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
     * @param petStatus 调用者宠物状态。⚠️ **Story 4.1 起不再参与过滤**（FR-83：V1.0.0「状态 B 用户
     *                  Feed 不显示成长日历」整条废止，公开内容对所有用户一视同仁）。形参保留仅为
     *                  兼容调用点，不读取；下一次改这条链路时可一并删除。
     * @param category  分类 Tab（ALL/DAILY/GROWTH_MOMENT/KNOWLEDGE）
     * @param cursor    上一批末尾游标 token；null = 首批
     * @param viewerId  当前登录用户 id（游客为 null）；非空则排除「本人已举报的帖」（内容审核 cm-6 §5.4）
     */
    @Transactional(readOnly = true)
    public FeedPageResponse loadFeed(String petStatus, String category, String cursor, Long viewerId) {
        FeedCategory cat = FeedCategory.parse(category);
        ContentType type = cat.toContentType();
        boolean requirePet = cat.requiresPet();

        FeedCursor decoded = (cursor == null || cursor.isBlank()) ? null : FeedCursor.decode(cursor);

        // 多取一条以判定 hasMore（不漏不重）。
        // 过滤口径（AD-4 Rule 2）：**一切消费公开内容的查询统一按 visibility = PUBLIC**，
        // 不按内容类型分支。过滤在 findFeed 内部固化，避免调用方漏传。
        List<ContentPost> rows = posts.findFeed(
                type, requirePet,
                viewerId != null, viewerId,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                PageRequest.of(0, PAGE_SIZE + 1));

        boolean hasMore = rows.size() > PAGE_SIZE;
        List<ContentPost> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;

        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(
                page.stream().map(ContentPost::getAuthorId).toList());
        Map<Long, Long> likeCounts = likeCounts(page);
        // V1.1.6 Story 3.1：两次新增的批量聚合。⚠️ 加起来每页多两次查询 ——
        // AD-7 Rule 3 已判定这是可接受代价，**不要为此加冗余计数列**。
        Set<Long> liked = likedIds(page, viewerId);
        Map<Long, Long> commentCounts = commentCounts(page, viewerId);

        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L),
                        liked.contains(p.getId()),
                        commentCounts.getOrDefault(p.getId(), 0L)))
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
        // AD-7 Rule 4：复用同一投影的出口**口径不得分叉** —— 与 loadFeed 走同一批聚合与同一个工厂。
        // 这里 viewer 恒为本人（「我的发布」），故已赞与评论数都按本人口径算。
        Set<Long> liked = likedIds(page, userId);
        Map<Long, Long> commentCounts = commentCounts(page, userId);
        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L),
                        liked.contains(p.getId()),
                        commentCounts.getOrDefault(p.getId(), 0L)))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new FeedPageResponse(items, nextCursor, hasMore);
    }
}

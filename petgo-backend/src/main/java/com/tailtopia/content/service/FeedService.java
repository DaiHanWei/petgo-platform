package com.tailtopia.content.service;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentVisibility;
import com.tailtopia.content.domain.PinObjectType;
import com.tailtopia.content.domain.PostStatus;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.FeedCategory;
import com.tailtopia.content.dto.ContentTagView;
import com.tailtopia.content.dto.FeedItemResponse;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.dto.PinnedSlotResponse;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.repository.CommentRepository.PostCommentCount;
import com.tailtopia.content.repository.ContentLikeRepository;
import com.tailtopia.content.repository.ContentLikeRepository.PostLikeCount;
import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Instant;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FeedService.class);

    /** Feed 每批条数（FR-17）。 */
    public static final int PAGE_SIZE = 20;

    private final ContentPostRepository posts;
    private final AccountQueryService accountQueryService;
    private final ContentLikeRepository likes;

    /** V1.1.6 Story 3.1：评论数批量聚合。⚠️ 只用批量方法，逐条那个是详情页的。 */
    private final CommentRepository comments;

    /** V1.1.6 Story 4.2：只首屏让位要知道当前顶置了哪条内容。 */
    private final ContentPinService pins;

    /** V1.1.6 Story 5.2：内容装饰标签，**整页一次批量**（AD-11）。 */
    private final ContentTagQueryService contentTags;

    public FeedService(ContentPostRepository posts, AccountQueryService accountQueryService,
            ContentLikeRepository likes, CommentRepository comments, ContentPinService pins,
            ContentTagQueryService contentTags) {
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.likes = likes;
        this.comments = comments;
        this.pins = pins;
        this.contentTags = contentTags;
    }

    /** 一页内容各自的装饰标签（整页一次查询）。 */
    private Map<Long, List<ContentTagView>> decorationTags(List<ContentPost> page) {
        return contentTags.findVisibleTags(page.stream().map(ContentPost::getId).toList(),
                Instant.now());
    }

    /**
     * 第一页要让位的那条内容 id（V1.1.6 Story 4.2 · AD-8 Rule 1）。
     *
     * <p>🛡 <b>只有第一页调用</b> —— 后续页仍可正常出现被顶置的内容。
     *
     * <p>🔴 <b>查顶置出错时当作没有顶置继续走</b>：AC 明写"顶置取数失败不得连带整个首页失败"。
     * 代价是那一次可能出现重复展示 —— 明确接受，比整个首页 500 好得多。
     * 这一处最容易漏：大家通常只想到"坑位端点挂了客户端不显示"，忘了首页内部也查了一次。
     */
    private Long pinnedContentIdToYield(String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            return null; // 只首屏让位
        }
        try {
            return pins.activePin(ContentPin.SLOT_HOME_FEED, Instant.now())
                    .map(ContentPin::getContentId)   // 推广卡片没有内容 id，天然不参与排除
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("查顶置失败，本次首页不让位（降级）", e);
            return null;
        }
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
        Long yieldId = pinnedContentIdToYield(cursor);

        // 多取一条以判定 hasMore（不漏不重）。
        // 过滤口径（AD-4 Rule 2）：**一切消费公开内容的查询统一按 visibility = PUBLIC**，
        // 不按内容类型分支。过滤在 findFeed 内部固化，避免调用方漏传。
        List<ContentPost> rows = posts.findFeed(
                type, requirePet,
                viewerId != null, viewerId,
                decoded != null,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                yieldId != null, yieldId,
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

        Map<Long, List<ContentTagView>> decorations = decorationTags(page);

        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L),
                        liked.contains(p.getId()),
                        commentCounts.getOrDefault(p.getId(), 0L),
                        decorations.get(p.getId())))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new FeedPageResponse(items, nextCursor, hasMore);
    }


    /**
     * 顶置坑位取数（V1.1.6 Story 4.2 · AC3 独立取数）。
     *
     * <p>返回的条目是**与普通条目完全同构的那一个 DTO**（同一个工厂、同一批聚合口径），
     * 客户端因此可以用同一个卡片组件渲染，只多挂一个角标 —— 而不是新写一套。
     *
     * <p>顶置内容若已不可展示（被删 / 挂起 / 转私密），视为坑位为空。
     * Story 4.1 的下架联动会即时结束排期，这里再兜一道，防止事件与查询之间的窗口。
     */
    @Transactional(readOnly = true)
    public PinnedSlotResponse loadPinnedSlot(String slot, Long viewerId) {
        ContentPin pin = pins.activePin(slot, Instant.now()).orElse(null);
        if (pin == null) {
            return new PinnedSlotResponse(null);
        }
        if (pin.getObjectType() == PinObjectType.PROMO) {
            // 推广卡片（Story 4.3）：不对应任何真实帖子，只有图片 / 标题 / 跳转目标三个字段。
            return new PinnedSlotResponse(new PinnedSlotResponse.Pinned(
                    pin.getId(), pin.getObjectType().name(), null,
                    new PinnedSlotResponse.Promo(
                            pin.getPromoImageUrl(), pin.getPromoTitle(), pin.getPromoLinkUrl())));
        }
        if (pin.getContentId() == null) {
            return new PinnedSlotResponse(null);
        }
        ContentPost post = posts.findById(pin.getContentId()).orElse(null);
        if (post == null || !isDisplayable(post)) {
            return new PinnedSlotResponse(null);
        }
        List<ContentPost> one = List.of(post);
        Map<Long, AuthorView> authors = accountQueryService.findAuthorViews(List.of(post.getAuthorId()));
        FeedItemResponse item = FeedItemResponse.of(post, authors.get(post.getAuthorId()),
                likeCounts(one).getOrDefault(post.getId(), 0L),
                likedIds(one, viewerId).contains(post.getId()),
                commentCounts(one, viewerId).getOrDefault(post.getId(), 0L),
                decorationTags(one).get(post.getId()));
        return new PinnedSlotResponse(new PinnedSlotResponse.Pinned(
                pin.getId(), pin.getObjectType().name(), item, null));
    }

    private static boolean isDisplayable(ContentPost post) {
        return post.getDeletedAt() == null
                && post.getStatus() == PostStatus.PUBLISHED
                && post.getVisibility() == ContentVisibility.PUBLIC;
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
        // ⚠️「我的发布」**不是** FR-75 列的三处展示位之一，故不查装饰标签 ——
        // 为一个不展示它的页面多发一次查询没有意义。真要展示时把这里换成 decorationTags(page) 即可。
        List<FeedItemResponse> items = page.stream()
                .map(p -> FeedItemResponse.of(p, authors.get(p.getAuthorId()),
                        likeCounts.getOrDefault(p.getId(), 0L),
                        liked.contains(p.getId()),
                        commentCounts.getOrDefault(p.getId(), 0L), null))
                .toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        return new FeedPageResponse(items, nextCursor, hasMore);
    }
}

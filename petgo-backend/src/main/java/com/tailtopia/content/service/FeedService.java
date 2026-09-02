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
import com.tailtopia.content.rank.FeedRankCursor;
import com.tailtopia.content.rank.FeedRecommendationService;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.tailtopia.social.read.UserHideRelationReader;

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

    /**
     * 隐藏关系只读端口（Story 4.4）。
     *
     * <p>⚠️ 依赖的是 {@code social.read} 的**接口**，不是它的 repository ——
     * content / notify / auth 三侧一律只依赖该端口（AD-8 的模块边界）。
     */
    private final UserHideRelationReader hideRelations;

    /**
     * 推荐序取数（V1.1.6 Story 16.3）。
     *
     * <p>🛡 <b>只有 ALL Tab 用它</b>；分类 Tab 走的是本类原有的时间倒序，一行代码没动。
     */
    private final FeedRecommendationService recommendations;

    public FeedService(ContentPostRepository posts, AccountQueryService accountQueryService,
            ContentLikeRepository likes, CommentRepository comments, ContentPinService pins,
            ContentTagQueryService contentTags, UserHideRelationReader hideRelations,
            FeedRecommendationService recommendations) {
        this.posts = posts;
        this.accountQueryService = accountQueryService;
        this.likes = likes;
        this.comments = comments;
        this.pins = pins;
        this.contentTags = contentTags;
        this.hideRelations = hideRelations;
        this.recommendations = recommendations;
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
     * <h2>🔴 两条独立路径，不是「推荐序 + 降级分支」</h2>
     * <table>
     *   <tr><th></th><th>ALL Tab</th><th>非 ALL Tab</th></tr>
     *   <tr><td>排序</td><td><b>推荐序</b>（Story 16.3）</td><td><b>纯时间倒序</b>（FR-17 既有逻辑）</td></tr>
     *   <tr><td>属性穿插 / 物种配比 / 防扎堆</td><td>✅</td><td>❌</td></tr>
     *   <tr><td>序列快照</td><td>✅ 需要</td><td>❌ 不需要（时间倒序 + 游标本就稳定）</td></tr>
     *   <tr><td>候选池全部过滤</td><td>✅</td><td>✅ 同样生效（这层与排序无关）</td></tr>
     * </table>
     * 理由：① 用户切到分类 Tab 的预期就是「筛出这一类、按时间看最新」；
     * ② 非 ALL 不需要序列快照，省掉一整套缓存开销、少一条出错路径；③ 回滚只需关掉 ALL 分支。
     *
     * <p>⚠️ <b>petStatus 形参已删除</b>（Story 16.3 · AC3）：Story 4.1 起它就不参与过滤
     * （FR-83 整条废止），留着只会让后人误以为它还有作用。
     *
     * @param category 分类 Tab（ALL/DAILY/GROWTH_MOMENT/KNOWLEDGE）
     * @param cursor   上一批末尾游标 token；null = 首批
     * @param viewerId 当前登录用户 id（游客为 null）；非空则排除「本人已举报的帖」（内容审核 cm-6 §5.4）
     * @param anonSessionId 游客的匿名会话 id（推荐序缓存键用；登录用户忽略）
     */
    @Transactional(readOnly = true)
    public FeedPageResponse loadFeed(String category, String cursor, Long viewerId,
            String anonSessionId) {
        FeedCategory cat = FeedCategory.parse(category);
        if (cat == FeedCategory.ALL) {
            return recommendedFeed(cursor, viewerId, anonSessionId);
        }
        return chronoFeed(cat, cursor, viewerId);
    }

    /**
     * ALL Tab：推荐序（Story 16.3）。
     *
     * <p>🔴 <b>降级链级别 4</b>：打分 / 依赖查询出任何异常 → <b>整体回落纯时间倒序</b>，用户无感。
     * 🛡 回落走的是 {@link #chronoFeed}，也就是<b>同一套候选池过滤</b> ——
     * AC4 明写「任何级别下候选池的全部过滤都不得被绕过」，回落时把过滤丢掉就是拉黑白拉。
     * ⚠️ 级别 4 <b>要告警</b>（级别 1、2 是预期行为不告警，见 §6.2）。
     *
     * <h2>🔴 发版过渡兼容：老客户端手上捏着旧 chrono 游标</h2>
     * 线上 v1.1.4 的 ALL Tab 走的是时序流 —— 发版切换瞬间，正在翻页的存量用户下一页
     * 带上来的是旧 {@link FeedCursor} 格式游标。直接按 rank 格式解会 422，
     * 而客户端拿着同一个 nextCursor 重试 → <b>死循环</b>。
     * 两种格式<b>可靠可分</b>（{@link FeedRankCursor#SEED_PREFIX} 的存在理由就是隔开编码空间）：
     * rank 是 {@code base64url("s<seed>:<consumed>")}，chrono 是 {@code base64url("<epochMicros>:<id>")}
     * ——首段一个强制 "s" 前缀、一个纯数字，互相解不开。
     * 解得成 chrono 就让这次会话<b>继续走时序流</b>（nextCursor 也回 chrono 格式，整个会话自洽）；
     * 两种格式都解不开才是真正的非法游标 → 422。
     */
    private FeedPageResponse recommendedFeed(String cursor, Long viewerId, String anonSessionId) {
        if (cursor != null && !cursor.isBlank() && rankCursorOrNull(cursor) == null) {
            if (chronoCursorOrNull(cursor) == null) {
                throw AppException.validation("游标无效"); // 两种格式都解不开才 422
            }
            // 老客户端（≤v1.1.4）的时序流会话：整个会话继续 chrono，游标自洽。
            return chronoFeed(FeedCategory.ALL, cursor, viewerId);
        }
        try {
            Long yieldId = pinnedContentIdToYield(cursor);
            FeedRecommendationService.RankedPage ranked =
                    recommendations.page(viewerId, anonSessionId, cursor, PAGE_SIZE, yieldId);
            return assemble(ranked.posts(), viewerId, ranked.nextCursor(), ranked.hasMore(),
                    FeedPageResponse.RANK_MODE_RECOMMEND);
        } catch (AppException e) {
            throw e; // 其余入参问题照常 422，不能被当成"算不出来"吞掉（游标格式已在上面兜过）
        } catch (RuntimeException e) {
            log.warn("{} cls={} msg={}", RANK_FALLBACK_MARKER, e.getClass().getSimpleName(),
                    e.getMessage());
            // 🛡 级别 4 的承诺是「用户无感」：走到这里 cursor 要么为 null、要么是 rank 格式
            //   （chrono 格式已在方法开头分流），而 chronoFeed 读不懂 rank 游标 ——
            //   原样透传会让降级路径自己 422（Redis 抖动时页 ≥2 全挂）。
            //   降级为返回 chrono 首页：丢的是阅读进度，保住的是可用性。
            return chronoFeed(FeedCategory.ALL, null, viewerId);
        }
    }

    /** 试按推荐序格式解游标；解不开返回 {@code null}（供 ALL Tab 双格式兼容分流判定，不抛）。 */
    private static FeedRankCursor rankCursorOrNull(String cursor) {
        try {
            return FeedRankCursor.decode(cursor);
        } catch (AppException e) {
            return null;
        }
    }

    /** 试按时间倒序格式解游标；解不开返回 {@code null}（同上）。 */
    private static FeedCursor chronoCursorOrNull(String cursor) {
        try {
            return FeedCursor.decode(cursor);
        } catch (AppException e) {
            return null;
        }
    }

    /** 告警锚点串（降级链级别 4）。🛡 改动即等于改动告警配置。 */
    static final String RANK_FALLBACK_MARKER = "feed-rank-fallback-to-chrono";

    /** 非 ALL Tab 与级别 4 回落：纯时间倒序（FR-17 既有逻辑，🛡 一行未动）。 */
    private FeedPageResponse chronoFeed(FeedCategory cat, String cursor, Long viewerId) {
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

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            ContentPost last = page.get(page.size() - 1);
            nextCursor = new FeedCursor(last.getCreatedAt(), last.getId()).encode();
        }
        // 🔴 分类 Tab 与级别 4 回落都走这里，两者都是 chrono ——
        // 客户端据此把降级期间的数据从推荐序效果里剔出去（Story 16.5）。
        return assemble(page, viewerId, nextCursor, hasMore,
                FeedPageResponse.RANK_MODE_CHRONO);
    }

    /**
     * 一页内容 → 一页 DTO。
     *
     * <p>🔴 <b>两条排序路径共用这一个组装口径</b>（AD-7 Rule 4）：客户端因此可以用同一个卡片组件，
     * 也不会出现「推荐序的卡少了评论数」这种分叉。新增字段只需改这一处。
     */
    private FeedPageResponse assemble(List<ContentPost> page, Long viewerId, String nextCursor,
            boolean hasMore, String rankMode) {
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
        return new FeedPageResponse(items, nextCursor, hasMore, rankMode);
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
        // 🔴 Story 4.4：作者被**当前查看者**隐藏 → 对该查看者视为坑位为空。
        //
        // 为什么 FR-68 正文里找不到这条：原 1.1.4 → 现 1.1.6 交叉重编号时，为保两版零耦合
        // 刻意没改 FR-68 正文，于是它只写了两条回退触发条件（坑位为空、顶置期间内容被下架），
        // **拉黑不在其中** —— 只读 FR-68 不会知道要加这层。
        // 而隐藏关系那个只读端口的注释里早就写明了：「若某个**运营干预位（顶置位**、推荐位等）
        // 命中被隐藏作者，则对该用户**视为该位为空**…**漏一处等于拉黑白拉**」。
        //
        // 🛡 **不新写过滤逻辑**，套的就是那一层（不区分 source ⇒ 主动拉黑与举报隐藏一次覆盖）。
        // 🛡 **不为该用户单独选替补顶置** —— 顶置是运营的编排结果，为某个人临时换一条会让
        //    「运营配了什么」变得不可预期、也无法解释。走 FR-68 既有的"位为空"回退即可。
        // ⚠️ 游客没有隐藏关系，整批短路、不发这次查询（沿用 findFeed 的既有惯例）。
        // ⚠️ 与 isDisplayable 是**两种不同性质的判定**，刻意不合成一个方法：
        //    前者与查看者无关（这条内容还在不在），后者因人而异（这个人愿不愿意看见）。
        if (viewerId != null && hideRelations.isHidden(viewerId, post.getAuthorId())) {
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

    /**
     * 委托给 {@link ContentDisplayability} —— <b>后台顶置列表也读同一条</b>（Story 11.1）。
     * 两处各写一遍的表现是「后台说生效中、App 坑位却是空的」，运营无从下手。
     */
    private static boolean isDisplayable(ContentPost post) {
        return ContentDisplayability.isDisplayable(post);
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
        // ⚠️「我的发布」不是 Feed 出口，rankMode 省略（Jackson NON_NULL）——
        // 给它填个 chrono 会让埋点侧把它算进首页排序的分母里。
        return new FeedPageResponse(items, nextCursor, hasMore, null);
    }
}

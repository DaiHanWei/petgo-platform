package com.tailtopia.auth.web;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.PublicProfileResponse;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开主页端点（V1.3.0 batch-b1 Story 2.1 · FR-118）。
 * {@code GET /api/v1/users/{userId}/profile}。
 *
 * <p><b>只读、游客可见</b>（点头像即看，无登录要求）—— 与迷你卡端点同一口径。
 *
 * <h2>它与 {@code /mini-profile} 的关系</h2>
 * 本 story 之后，App 里每一处头像入口（Feed 头像 / 详情页作者行 / 评论区头像 /
 * 场所详情的标记人 / 场所评论头像）**一律进完整主页**，迷你卡组件零引用。
 * 迷你卡端点本身**暂时保留**（服务端删接口属另一次清理，而且这次改动已经够大了）。
 *
 * <h2>🔴 「不存在的 id」与「已注销」刻意不可区分</h2>
 * 两者都返回 <b>200 + 一个身份字段都没有的投影</b>，<b>不发 404</b>。
 * 能区分「这个 id 从没存在过」与「这个 id 注销了」，就等于给了一个按 id 遍历、
 * 确认谁注册过的枚举口子。客户端两侧也用同一句文案。
 *
 * <h2>🔴 主动拉黑者不可进入对方主页（FR-94 第 4 条 / AD-11）</h2>
 * 逐条照抄 {@code MiniProfileController}：拦在取数之前，命中即 403，不触碰任何展示字段
 * （"200 + 标记字段"等于拦了一半）。
 * <p>⚠️ **只认 BLOCK**：举报隐藏照常放行 —— 「已举报」状态与重复举报入口全靠它。
 *
 * <h2>🔴 viewer 只认 {@code role=USER}</h2>
 * 本端点 permitAll，兽医 token 也进得来。兽医的 {@code sub=vetId} 与 {@code users.id}
 * 是两个会碰撞的命名空间，用它查 {@code isBlocked}/{@code isReported} 就是拿无关用户的
 * 隐藏关系做判断（安全评审三轮 #1 的原话）。
 */
@RestController
public class PublicProfileController {

    private final AccountQueryService accounts;
    private final ContentService contentService;
    private final FeedService feedService;
    private final UserHideRelationReader hideRelations;

    public PublicProfileController(AccountQueryService accounts, ContentService contentService,
            FeedService feedService, UserHideRelationReader hideRelations) {
        this.accounts = accounts;
        this.contentService = contentService;
        this.feedService = feedService;
        this.hideRelations = hideRelations;
    }

    @GetMapping("/api/v1/users/{userId}/profile")
    public PublicProfileResponse profile(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long userId) {
        Long viewerId = viewerId(jwt);
        // FR-94 第 4 条：主动拉黑者不可再进入对方主页。必须服务端校验 ——
        // 纯前端拦截可被推送深链、通知中心历史、外部分享链接绕过（架构 S2）。
        if (viewerId != null && hideRelations.isBlocked(viewerId, userId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        // 🔴 **不存在的 id 与已注销用户走同一条出口，刻意不可区分**。
        // {@link AccountQueryService#findAuthorViews} 对查不到的 id **补齐匿名投影**
        // （`deleted=true`），所以下面那个 `deleted()` 分支同时吃掉这两种情况 ——
        // 这正是想要的：能区分「这个 id 从没存在过」与「这个 id 注销了」，
        // 就等于给了一个**按 id 遍历、确认谁注册过**的枚举口子。
        // ⚠️ `getOrDefault` 是防御：万一将来那边不再补齐，这里也不能 NPE 成 500
        // （还会把"查了个不存在的 id"变成一条堆栈日志）。
        AuthorView author = accounts.findAuthorViews(List.of(userId))
                .getOrDefault(userId, AuthorView.anonymized(userId));
        if (author.deleted()) {
            // AC5：一个身份字段都不给（NFR-8）。注销与「从没这个 id」在这里合流。
            return PublicProfileResponse.deactivated();
        }

        // 加入时间 = users.created_at（AC1 明说是本批次新补的字段）。
        var joinedAt = accounts.findUserById(userId).map(u -> u.getCreatedAt()).orElse(null);
        // ⚠️ 游客传 null：Jackson NON_NULL 会把这个键整个省略，游客响应体 key 集合一字未变。
        Boolean reported = viewerId == null ? null : hideRelations.isReported(viewerId, userId);
        return PublicProfileResponse.of(author,
                accounts.activeSignatureOf(userId).orElse(null),
                joinedAt,
                // ⚠️ 发帖总数**复用既有统计**（Dev Notes 明确说别重新实现）；
                //    Story 2.2 起它只数 PUBLIC —— 与下面那个内容区口径同源，
                //    否则页面上「18 postingan」配一个 12 格的网格，差值就是私密内容条数。
                contentService.countPublicPostsByAuthor(userId),
                // 获赞总数：本批次新补（Story 2.2 · AC2）。一条 SQL 出数，不新增冗余计数列。
                contentService.sumLikesOnPublicPostsByAuthor(userId),
                viewerId != null && viewerId == userId,
                reported);
    }

    /**
     * 公开主页的**内容区**（V1.3.0 batch-b1 Story 2.2 · FR-118.2 · AC1）。
     * {@code GET /api/v1/users/{userId}/posts?cursor=}
     *
     * <p>目标用户全部 PUBLIC 内容，三类混排不分流、时间倒序、游标分页。
     * 投影与 Feed / 「我的发布」**同一个**（{@code FeedItemResponse}），点进去就是既有内容详情页。
     *
     * <h2>🔴 拉黑守卫与 {@code /profile} 完全一致</h2>
     * 两个端点<b>各自都要拦</b>：只拦主页那条，等于留了一个「绕过主页直接拉他内容列表」的口子，
     * 而 FR-94 第 4 条要挡的就是「主动拉黑者不该再看到对方」。
     *
     * <h2>⚠️ 已注销 / 不存在 → 空页，不是 404</h2>
     * 与 {@code /profile} 同口径：**可区分就等于给了一个按 id 遍历确认谁注册过的枚举口子**。
     * 注销用户的内容本就在 {@code findPublicPostsByAuthor} 里查不到（注销走批量隐藏），
     * 这里不另加分支。
     */
    @GetMapping("/api/v1/users/{userId}/posts")
    public FeedPageResponse posts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long userId,
            @RequestParam(required = false) String cursor) {
        Long viewerId = viewerId(jwt);
        if (viewerId != null && hideRelations.isBlocked(viewerId, userId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        return feedService.userPublicPosts(userId, viewerId, cursor);
    }

    /** 登录<b>用户</b> id（游客 / 无效 JWT / 非 USER 角色 → null）。见类注释。 */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null
                || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

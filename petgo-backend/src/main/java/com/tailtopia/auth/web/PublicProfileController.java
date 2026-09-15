package com.tailtopia.auth.web;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.PublicProfileResponse;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final UserHideRelationReader hideRelations;

    public PublicProfileController(AccountQueryService accounts, ContentService contentService,
            UserHideRelationReader hideRelations) {
        this.accounts = accounts;
        this.contentService = contentService;
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
                // ⚠️ 发帖总数**复用既有统计**（AC1 的 Dev Notes 明确说别重新实现）。
                contentService.countPublishedByAuthor(userId),
                viewerId != null && viewerId == userId,
                reported);
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

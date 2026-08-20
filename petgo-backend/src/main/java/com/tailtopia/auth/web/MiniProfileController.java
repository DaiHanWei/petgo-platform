package com.tailtopia.auth.web;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.MiniProfileResponse;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 他人迷你主页投影端点（Story 3.8，FR-26）。{@code GET /api/v1/users/{userId}/mini-profile}。
 *
 * <p>**只读、游客可见**（点头像即看，无登录要求）。nickname/avatar/signature 经 {@link AccountQueryService}、
 * postCount 经 {@link ContentService}（**不直 join content 表**）。已注销 → isDeactivated=true（前端不弹卡）。
 *
 * <p><b>Story 1.1（V1.1.4）新增主动拉黑拦截（FR-94 生效范围第 4 条，AD-11）</b>：
 * 端点<b>保持 {@code permitAll}</b>，照抄 {@code ContentDetailController} 的「游客可读 + 登录者可识别」模式
 * 解出<b>可选</b> viewer；<b>游客行为一字不改</b>。
 */
@RestController
public class MiniProfileController {

    private final AccountQueryService accountQueryService;
    private final ContentService contentService;
    private final UserHideRelationReader hideRelations;

    public MiniProfileController(AccountQueryService accountQueryService, ContentService contentService,
            UserHideRelationReader hideRelations) {
        this.accountQueryService = accountQueryService;
        this.contentService = contentService;
        this.hideRelations = hideRelations;
    }

    @GetMapping("/api/v1/users/{userId}/mini-profile")
    public MiniProfileResponse miniProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable long userId) {
        Long viewerId = viewerId(jwt);
        // FR-94 第 4 条：主动拉黑者不可再进入对方主页。必须服务端校验——纯前端拦截可被推送深链、
        // 通知中心历史记录、外部分享链接绕过（架构 S2）。
        // ⚠️ 只认 BLOCK：举报隐藏照常放行，「已举报」状态与重复举报入口全靠它（AD-11 / FR-58 闭环）。
        // 拦在取数之前：命中即返回，不触碰任何展示字段（AD-11：「200 + 标记字段」等于拦了一半）。
        if (viewerId != null && hideRelations.isBlocked(viewerId, userId)) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        AuthorView author = accountQueryService.findAuthorViews(java.util.List.of(userId)).get(userId);
        // 该 id 根本不存在 → 投影里没有这一行，get 返回 null。必须显式 404：
        // 直接点 author.deleted() 会 NPE，对外表现是 500（还会把「查了个不存在的 id」这件事
        // 变成一条堆栈日志）。注销与不存在都不外泄身份信息，但状态码不能混。
        if (author == null) {
            throw AppException.notFound("用户不存在");
        }
        if (author.deleted()) {
            return MiniProfileResponse.deactivated(); // 注销不暴露身份信息（NFR-8）
        }
        // Story 2.1 AC8：「已举报」由 REPORT 隐藏行是否存在派生（服务端持久化，不是前端会话态）——
        // 用户重装 App 也得还看得到，否则他会重复举报同一个人，而每次都会真的落一行明细。
        // ⚠️ 游客传 null：Jackson NON_NULL 会把这个键整个省略，游客响应体的 key 集合一字未变。
        Boolean reported = viewerId == null ? null : hideRelations.isReported(viewerId, userId);
        return MiniProfileResponse.of(author,
                accountQueryService.activeSignatureOf(userId).orElse(null),
                contentService.countPublishedByAuthor(userId),
                reported);
    }

    /**
     * 登录<b>用户</b> id（游客 / 无效 JWT / <b>非 USER 角色</b> → null）。
     *
     * <p>⚠️ 本端点 permitAll，兽医 token 也进得来。必须只认 {@code role=USER}——否则兽医的
     * {@code sub=vetId} 会被当成碰撞的 {@code users.id}，用它查 {@code isBlocked}/{@code isReported}
     * 就是拿无关用户的隐藏关系做判断（安全评审三轮 #1）。非用户一律按游客投影。
     */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

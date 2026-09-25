package com.tailtopia.place.web;

import com.tailtopia.place.dto.PlaceCommentCreateRequest;
import com.tailtopia.place.dto.PlaceCommentPageResponse;
import com.tailtopia.place.dto.PlaceCommentResponse;
import com.tailtopia.place.service.PlaceCommentQueryService;
import com.tailtopia.place.service.PlaceCommentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场所评论端点（V1.3.0 batch-b1 Story 1.7 · FR-112.3）。
 *
 * <ul>
 *   <li>{@code GET  /api/v1/places/{token}/comments} — 评论列表，**游客可读**（同详情口径）；</li>
 *   <li>{@code POST /api/v1/places/{token}/comments} — 发表一级评论（≤200 字 + 可选态度），需 JWT；</li>
 *   <li>{@code DELETE /api/v1/place-comments/{id}} — 删除**自己的**评论（AC7），需 JWT。</li>
 * </ul>
 *
 * <h2>🔴 为什么不写进 {@code PlaceController}</h2>
 * 那个类有一条反射测试钉着「**一个 PUT/PATCH/DELETE 都不许有**」（用户不可修改/删除场所）。
 * 把删除评论的端点塞进去会让那条测试变红 —— 而它守的是另一件事，不该为了腾地方去改它。
 * 两个 controller、边界分明：<b>场所本体不可写，场所上的评论可写</b>。
 *
 * <h2>⚠️ 评论 id 是自增数字，这是**跟随既有评论端点**的一致性选择</h2>
 * 基线护栏「对外暴露标识一律不可枚举 token」针对的是**可被枚举出内容的资源**
 * （场所 / 帖子 / 分享页 —— 拿 id 递增就能爬全站）。评论 id 在既有
 * {@code DELETE /api/v1/comments/{id}} 上已经是数字，而且：
 * 拿到一个陌生的评论 id **什么也做不了** —— 删除要过「是不是本人」，列表不按 id 寻址。
 * 换成 token 只会让客户端同时维护两套评论寻址方式。
 *
 * <h2>🔴 没有"回复"端点，永远不会有</h2>
 * 场所评论只有一级（PRD ③ / AC2）：没有 {@code /replies}，请求体里也没有 {@code parentId}。
 * {@code PlaceCommentControllerShapeTest} 把这条钉成了可证伪的测试。
 */
@RestController
public class PlaceCommentController {

    /** 发评论限流：30/分钟。正常人打不了这么快，挡的是脚本刷评论。 */
    private static final int CREATE_LIMIT = 30;
    private static final Duration CREATE_WINDOW = Duration.ofMinutes(1);

    private final PlaceCommentService service;
    private final PlaceCommentQueryService query;
    private final RedisRateLimiter rateLimiter;

    public PlaceCommentController(PlaceCommentService service, PlaceCommentQueryService query,
            RedisRateLimiter rateLimiter) {
        this.service = service;
        this.query = query;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 评论列表（AC6）。🔒 游客可读 —— 所以这里的 {@code jwt} **可能为 null**，
     * 不能走 {@code currentUserId}（那会抛 401）。
     *
     * <p>viewer 维度过滤（审核态 + 拉黑 R1）全在服务端做：客户端过滤只是"看不见"，抓包照样拿得到。
     */
    @GetMapping("/api/v1/places/{token}/comments")
    public PlaceCommentPageResponse list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @RequestParam(required = false) String cursor) {
        return query.list(token, cursor, optionalUserId(jwt));
    }

    /** 发表评论（AC2/AC3/AC5）。201。命中 L1 词库 → 422；场所不存在/已下架 → 404。 */
    @PostMapping("/api/v1/places/{token}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCommentResponse create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String token,
            @Valid @RequestBody PlaceCommentCreateRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:place:comment:" + userId, CREATE_LIMIT, CREATE_WINDOW);
        var saved = service.create(token, userId, req);
        // 刚发出的那条一定是自己的、一定是 UNDER_REVIEW（先发后审）——
        // 作者投影直接用 viewer 自己的，省一次查询。
        return PlaceCommentResponse.of(saved, query.authorViewOf(userId), userId);
    }

    /**
     * 删除自己的评论（AC7）。204。
     *
     * <p>🔒 **服务端校验操作者是作者本人** —— 不是"传个 id 就能删"。403 而不是 404：
     * 这条评论就在公开列表里，它存在这件事不是秘密（与场所下架那条 404 的处理不同）。
     */
    @DeleteMapping("/api/v1/place-comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.deleteOwn(id, currentUserId(jwt));
    }

    /**
     * 游客路径用的「当前用户」：没有 JWT、或 JWT 不是 {@code role=USER} → null，**不抛 401**。
     *
     * <p>🔴 **必须看 role，不能只看 sub**：兽医 token 的 {@code sub} 是 **vetId**，
     * 与 {@code users.id} 是两个会大量碰撞的命名空间。只认 sub 的话，一个兽医带着自己的
     * token 打开场所详情，就会被当成「users.id 恰好等于该 vetId 的那个用户」——
     * 于是**那个人尚未过审 / 已被下架的评论原文会下发给他**，还标着 `mine=true`
     * （删除会被服务端拦下，但内容已经泄漏了）。
     * <p>写端点靠 SecurityConfig 的 `hasRole("USER")` 挡住了同一个坑；读端点对游客放行，
     * 挡不住，所以这道门只能写在这里。
     */
    private static Long optionalUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        // 非 USER 角色（兽医 / 运营）一律按游客读：他们的 sub 不是 users.id。
        if (!"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null; // 凭证形状不对 → 当游客读，写端点那侧才明确 401
        }
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}

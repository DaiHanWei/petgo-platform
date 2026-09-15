package com.tailtopia.place.web;

import com.tailtopia.moderation.dto.ReportRequest;
import com.tailtopia.place.domain.GeoBox;
import com.tailtopia.place.dto.PlaceCreateRequest;
import com.tailtopia.place.dto.PlaceCreatedResponse;
import com.tailtopia.place.dto.PlaceDetailResponse;
import com.tailtopia.place.dto.PlaceListResponse;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.place.service.PlaceService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场所只读端点（V1.3.0 batch-b1 Story 1.1 · FR-112.2）。
 *
 * <p>🔴 <b>GET 对游客放行</b>（见 {@code SecurityConfig}）：场所列表是「这个功能里已经攒了些
 * 什么地方」的展示面，用登录墙拦它没有任何意义 —— 同 Toko 商品列表的既定取舍。
 * App 侧对应地<b>不把 {@code /places} 放进 {@code _controlledLocations}</b>（Story 1.1 Dev Notes）。
 * 写端点（标记场所，Story 1.3）仍需 JWT。
 *
 * <p>🔴 <b>路径与返回体都不出现自增 id</b>：对外寻址只用不可枚举 token（AD-1 Rule 3）。
 *
 * <p><b>只读，而且没有编辑端点</b>：用户不可修改场所（2026-09-15 拍板）——
 * 服务端<b>不提供</b>任何 PATCH/PUT，纠错走后台 AB-17A。要加编辑接口，先回决策日志改口径。
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    /** 标记场所限流：20/分钟。低频动作，挡的是脚本批量灌场所。 */
    private static final int MARK_LIMIT = 20;
    private static final Duration MARK_WINDOW = Duration.ofMinutes(1);

    /** 举报限流：10/分钟。重复举报本身幂等，这条挡的是换着场所刷工单。 */
    private static final int REPORT_LIMIT = 10;
    private static final Duration REPORT_WINDOW = Duration.ofMinutes(1);

    private final PlaceQueryService query;
    private final PlaceService placeService;
    private final RedisRateLimiter rateLimiter;

    public PlaceController(PlaceQueryService query, PlaceService placeService,
            RedisRateLimiter rateLimiter) {
        this.query = query;
        this.placeService = placeService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 场所列表（Story 1.1 AC2 / Story 1.2 AC1）。
     *
     * <p>带 {@code lat}+{@code lng} → 按直线距离升序；不带 → 按创建时间倒序。
     * 「按最新」是**默认路径而不是降级路径** —— 无定位权限是 PRD ② 明定的正常态。
     *
     * <p>🔴 <b>坐标只能同时给或同时不给</b>，且必须落在合法区间（纬度 ±90 / 经度 ±180）：
     * 违反即 <b>422</b>，不静默忽略。静默忽略会让客户端拿到「按最新」的列表却以为是按距离排的 ——
     * 用户看到的是「最近的店在 20 公里外」，而没有任何地方能看出坐标其实没送到
     * （同 {@code ShopProductController} 对非法品类的处理）。
     *
     * <p>🛡 <b>坐标绝不进日志</b>（NFR-4/NFR-5）：这里不打任何带 lat/lng 的日志，
     * 校验失败的 ProblemDetail 里也不回显坐标值。
     */
    @GetMapping
    public PlaceListResponse list(@RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        if ((lat == null) != (lng == null)) {
            throw AppException.validation("经纬度必须同时提供");
        }
        if (lat != null && !GeoBox.isValidCoordinate(lat, lng)) {
            throw AppException.validation("坐标超出合法范围");
        }
        return query.list(lat, lng);
    }

    /**
     * 标记一个场所（Story 1.3 · AC1/AC6/AC8）。需登录；201 + 不可枚举 token。
     *
     * <p>字段级校验由 {@code @Valid} 完成 → 违反即 <b>422 ProblemDetail</b>（字段内联错误由客户端渲染，
     * AC7：客户端在必填未满时**根本不发这个请求**，保存按钮是灰的）。
     *
     * <p>写端点限流：与内容发布同一范式。标记场所是低频动作，20/分钟远高于真实使用，
     * 挡的是脚本批量灌场所。
     *
     * <p>🔴 <b>{@code Idempotency-Key} 头不是可选的加分项</b>：用户既不能编辑也不能删除场所，
     * 丢一个 201（弱网下很常见）+ 客户端重试 = 一个<b>永久重复</b>的场所，只能等运营去后台合并。
     *
     * <p>🔒 <b>仅 {@code role=USER}</b>（{@code SecurityConfig} 显式限定）：本方法把
     * {@code jwt.sub} 当 {@code users.id} 用，而<b>兽医 token 的 sub 是 vetId</b>、
     * 与 {@code users.id} 是两个会大量碰撞的命名空间。落到
     * {@code anyRequest().authenticated()} 的话，兽医能以一个无关用户的名义创建场所，
     * 而 {@code created_by} 没有外键、会被静默写进去（同拉黑/举报端点显式限定的理由）。
     *
     * <h2>🔴 这个类里**只有** GET 列表 + POST 创建，永远不会有 PUT/PATCH/DELETE</h2>
     * 本版用户不可修改、也不可删除自己标记的场所（2026-09-15 拍板），纠错走后台 AB-17A。
     * {@code PlaceControllerNoEditEndpointTest} 用反射把这条约束钉成了**可证伪的测试** ——
     * 谁加一个写端点，那条测试就会红。要加之前先回决策日志改口径。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceCreatedResponse mark(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PlaceCreateRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:place:mark:" + userId, MARK_LIMIT, MARK_WINDOW);
        return PlaceCreatedResponse.from(placeService.mark(userId, req, idempotencyKey));
    }

    /**
     * 场所详情（Story 1.5 · AC1/AC7）。🔒 GET 对游客放行。
     *
     * <p>带 {@code lat}+{@code lng} 时回距离位（AC1 的「距离」）。
     *
     * <p>🔴 <b>下架 / 不存在一律 404 且文案相同</b>（AC7）：客户端两种情况都落同一个
     * 「场所不存在」空态。让两者可区分等于泄漏「这个 token 曾经存在」。
     */
    @GetMapping("/{token}")
    public PlaceDetailResponse detail(@PathVariable String token,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return query.detail(token, lat, lng);
    }

    /**
     * 举报一个场所（Story 1.5 · AC5）。需登录；202。
     *
     * <p><b>复用既有五类原因</b>（{@code ReportRequest} / {@code ReportReason}）——
     * AC5 要求那张抽屉「文案一字不改」，所以取值域也必须是同一个枚举，不另抄一份。
     *
     * <p>写工单 PENDING 进运营队列、**不触发任何自动下架**（同内容举报）；重复举报幂等。
     */
    @PostMapping("/{token}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @Valid @RequestBody ReportRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:place:report:" + userId, REPORT_LIMIT, REPORT_WINDOW);
        placeService.report(token, userId, req.reasonType());
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

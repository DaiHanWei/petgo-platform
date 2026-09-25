package com.tailtopia.place.web;

import com.tailtopia.place.dto.PlacePhotoContributeRequest;
import com.tailtopia.place.service.PlacePhotoService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为场所补充照片（V1.3.0 batch-b1 Story 1.9 · FR-112.3）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/places/{token}/photos} — 补充照片（需 JWT）；</li>
 *   <li>{@code DELETE /api/v1/place-photos/{id}} — 删除**自己传的**那张（需 JWT）。</li>
 * </ul>
 *
 * <h2>🔴 谁都能补，不只是标记人（AC1）</h2>
 * 场所是共享的地点条目 —— 标记人连场所本身都改不了。所以这里**没有**"只有标记人能加"的判断。
 *
 * <h2>为什么不写进 {@code PlaceController}</h2>
 * 同 {@code PlaceCommentController}：那个类有一条反射测试钉着「一个 PUT/PATCH/DELETE 都不许有」
 * （用户不可修改/删除**场所本体**）。边界：<b>场所本体不可写，场所上的评论与照片可写</b>。
 *
 * <p>⚠️ **读取没有独立端点**：照片是详情响应的一部分（`GET /places/{token}` 的 `photos`），
 * 单开一个 `GET /photos` 只会多一条要同步维护的可见性过滤。
 */
@RestController
public class PlacePhotoController {

    /** 补充照片限流：10/分钟。一次最多 9 张，这条挡的是脚本刷图。 */
    private static final int CONTRIBUTE_LIMIT = 10;
    private static final Duration CONTRIBUTE_WINDOW = Duration.ofMinutes(1);

    private final PlacePhotoService service;
    private final RedisRateLimiter rateLimiter;

    public PlacePhotoController(PlacePhotoService service, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    /** 补充照片（AC1/AC3）。202：落库了但还没过审 —— 201 会让人以为它已经对所有人可见。 */
    @PostMapping("/api/v1/places/{token}/photos")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void contribute(@AuthenticationPrincipal Jwt jwt, @PathVariable String token,
            @Valid @RequestBody PlacePhotoContributeRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:place:photo:" + userId, CONTRIBUTE_LIMIT, CONTRIBUTE_WINDOW);
        service.contribute(token, userId, req);
    }

    /**
     * 删除自己传的照片。204。
     *
     * <p>🔒 服务端校验上传者本人（403）。⚠️ **标记人也不能删别人补的照片** ——
     * 同「标记人不能删别人的评论」：场所没有主人。违规照片走举报 → 运营下架（AB-17A）。
     */
    @DeleteMapping("/api/v1/place-photos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.deleteOwn(id, currentUserId(jwt));
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

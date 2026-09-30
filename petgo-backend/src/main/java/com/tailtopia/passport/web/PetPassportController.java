package com.tailtopia.passport.web;

import com.tailtopia.passport.dto.PassportSnapshotDetailResponse;
import com.tailtopia.passport.dto.PassportSnapshotListResponse;
import com.tailtopia.passport.dto.PassportSnapshotPurchaseResponse;
import com.tailtopia.passport.dto.PetPassportResponse;
import com.tailtopia.passport.service.PassportSnapshotService;
import com.tailtopia.passport.service.PetPassportService;
import com.tailtopia.purchase.dto.KeepsakePayRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物护照（V1.3.2 batch-a Story 1.2 · AC3）。仅 {@code role=USER}（SecurityConfig 精确 matcher）。
 */
@RestController
public class PetPassportController {

    private static final String SNAPSHOTS = "/api/v1/pet-profiles/me/passport/snapshots";
    /** 快照发起限流（Story 3.4 · AC3.6）：10 次 / 分钟 / 用户。 */
    private static final int SNAPSHOT_LIMIT = 10;
    private static final Duration SNAPSHOT_WINDOW = Duration.ofMinutes(1);

    private final PetPassportService passports;
    private final PassportSnapshotService snapshots;
    private final RedisRateLimiter rateLimiter;

    public PetPassportController(PetPassportService passports, PassportSnapshotService snapshots,
            RedisRateLimiter rateLimiter) {
        this.passports = passports;
        this.snapshots = snapshots;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 本人宠物的护照 + 全部章。
     *
     * <p>🔴 <b>GET 里签发</b>（D-6「首次进护照页」）：{@code ensureIssued} 幂等，重复 GET 无副作用；
     * 因此 {@link PetPassportService#pageFor} 是<b>写事务、不是 readOnly</b>。别另开 POST 让 App 先调 ——
     * 那会多一种「页面开了但没签发」的中间态。
     *
     * <p>无宠物档案 → 404 {@code not-found}（与时间线 {@code requireProfile} 同口径）。
     */
    @GetMapping("/api/v1/pet-profiles/me/passport")
    public PetPassportResponse passport(@AuthenticationPrincipal Jwt jwt) {
        return passports.pageFor(currentUserId(jwt));
    }

    /** 发起「当前版本」快照购买（Story 3.4 · AC3）：无章 422、已买 409；响应带 {@code snapshotToken}。 */
    @PostMapping(SNAPSHOTS)
    public PassportSnapshotPurchaseResponse startSnapshot(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody KeepsakePayRequest req) {
        long userId = currentUserId(jwt);
        rateLimiter.check("rl:passport:snapshot:" + userId, SNAPSHOT_LIMIT, SNAPSHOT_WINDOW);
        return snapshots.start(userId, req.channel());
    }

    /** 已购版本（Story 3.4 · AC7.1）：只列已付，按 paidAt 倒序。 */
    @GetMapping(SNAPSHOTS)
    public PassportSnapshotListResponse listSnapshots(@AuthenticationPrincipal Jwt jwt) {
        return snapshots.list(currentUserId(jwt));
    }

    /** 回看（Story 3.4 · AC7.2）：非本人 / 未付 / 不存在 → 404。 */
    @GetMapping(SNAPSHOTS + "/{token}")
    public PassportSnapshotDetailResponse snapshot(@AuthenticationPrincipal Jwt jwt, @PathVariable String token) {
        return snapshots.get(currentUserId(jwt), token);
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

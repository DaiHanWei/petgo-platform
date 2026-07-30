package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.HealthListItemResponse;
import com.tailtopia.profile.dto.HealthRecordCreateRequest;
import com.tailtopia.profile.dto.HealthRecordResponse;
import com.tailtopia.profile.dto.HealthRecordUpdateRequest;
import com.tailtopia.profile.service.HealthRecordService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化健康记录端点（Story 7.1，FR-45/45A）。当前用户档案子资源
 * {@code /api/v1/pet-profiles/me/health-records}（与 /me/milestones、/me/timeline 同范式）。
 *
 * <p>owner 取自 JWT，绝不信任客户端传入。{@code {id}} 仅在鉴权 owner 作用域内可寻址（记录须归属当前用户
 * 宠物，否则 404 防枚举）——单宠物 owner 子资源，不跨用户枚举出数据。无档案 → 404。
 */
@RestController
@RequestMapping("/api/v1/pet-profiles/me/health-records")
public class HealthRecordController {

    private static final int WRITE_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final HealthRecordService service;
    private final RedisRateLimiter rateLimiter;

    public HealthRecordController(HealthRecordService service, RedisRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    /** 健康时间线混排（Story 7.2）：结构化记录（editable）+ 问诊存档（只读）按 event_date 倒序。无档案 → 404。 */
    @GetMapping
    public List<HealthListItemResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.timeline(currentUserId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HealthRecordResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody HealthRecordCreateRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:health-record:write:" + ownerId, WRITE_LIMIT, WINDOW);
        return service.create(ownerId, req);
    }

    @PatchMapping("/{id}")
    public HealthRecordResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody HealthRecordUpdateRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:health-record:write:" + ownerId, WRITE_LIMIT, WINDOW);
        return service.update(ownerId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(currentUserId(jwt), id);
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

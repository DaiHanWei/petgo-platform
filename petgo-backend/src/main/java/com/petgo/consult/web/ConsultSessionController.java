package com.petgo.consult.web;

import com.petgo.consult.domain.ConsultSource;
import com.petgo.consult.dto.ConsultSessionResponse;
import com.petgo.consult.dto.CreateConsultSessionRequest;
import com.petgo.consult.service.ConsultSessionService;
import com.petgo.consult.service.ConsultSessionService.CreateResult;
import com.petgo.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧咨询会话端点（Story 5.3，{@code hasRole('USER')}）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/consult-sessions}：发起（DIRECT）→ WAITING（已有占用态则回现有，alreadyActive=true）。</li>
 *   <li>{@code GET /api/v1/consult-sessions/{id}}：轮询状态（含 timedOut，供超时弹层）。</li>
 *   <li>{@code PATCH /api/v1/consult-sessions/{id}/continue-waiting}：继续等待（重置计时）。</li>
 *   <li>{@code DELETE /api/v1/consult-sessions/{id}}：取消（WAITING → CANCELLED + 出队）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/consult-sessions")
public class ConsultSessionController {

    private final ConsultSessionService service;

    public ConsultSessionController(ConsultSessionService service) {
        this.service = service;
    }

    @PostMapping
    public ConsultSessionResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) CreateConsultSessionRequest req) {
        CreateResult result = service.createWaiting(currentUserId(jwt), ConsultSource.DIRECT);
        return ConsultSessionResponse.of(result.session(),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, result.alreadyActive());
    }

    /** 当前用户的占用态会话（无则 204）。入口据此显示「查看进行中 →」。 */
    @GetMapping("/active")
    public org.springframework.http.ResponseEntity<ConsultSessionResponse> active(
            @AuthenticationPrincipal Jwt jwt) {
        return service.findActiveForUser(currentUserId(jwt))
                .map(s -> org.springframework.http.ResponseEntity.ok(
                        ConsultSessionResponse.of(s, ConsultSessionService.WAITING_TIMEOUT_SECONDS, true)))
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ConsultSessionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return ConsultSessionResponse.of(service.getForUser(currentUserId(jwt), id),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, false);
    }

    @PatchMapping("/{id}/continue-waiting")
    public ConsultSessionResponse continueWaiting(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return ConsultSessionResponse.of(service.continueWaiting(currentUserId(jwt), id),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, false);
    }

    @DeleteMapping("/{id}")
    public ConsultSessionResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return ConsultSessionResponse.of(service.cancel(currentUserId(jwt), id),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, false);
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

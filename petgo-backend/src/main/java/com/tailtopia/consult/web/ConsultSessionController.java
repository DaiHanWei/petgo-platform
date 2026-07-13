package com.tailtopia.consult.web;

import com.tailtopia.consult.domain.ConsultSource;
import com.tailtopia.consult.dto.ConsultAiContextResponse;
import com.tailtopia.consult.dto.ConsultSessionResponse;
import com.tailtopia.consult.dto.CreateConsultSessionRequest;
import com.tailtopia.consult.dto.SubmitRatingRequest;
import com.tailtopia.consult.service.ConsultAiContextService;
import com.tailtopia.consult.service.ConsultCloseService;
import com.tailtopia.consult.service.ConsultSessionService;
import com.tailtopia.consult.service.ConsultSessionService.CreateResult;
import com.tailtopia.shared.error.AppException;
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
    private final ConsultCloseService closeService;
    private final ConsultAiContextService aiContextService;
    private final com.tailtopia.consult.service.ConsultSuspensionService suspensionService;

    public ConsultSessionController(ConsultSessionService service, ConsultCloseService closeService,
            ConsultAiContextService aiContextService,
            com.tailtopia.consult.service.ConsultSuspensionService suspensionService) {
        this.service = service;
        this.closeService = closeService;
        this.aiContextService = aiContextService;
        this.suspensionService = suspensionService;
    }

    @PostMapping
    public ConsultSessionResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) CreateConsultSessionRequest req) {
        long userId = currentUserId(jwt);
        CreateResult result;
        if (req != null && req.isAiUpgrade()) {
            if (req.triageTaskId() == null) {
                throw AppException.validation("升级兽医需提供 triageTaskId");
            }
            result = service.createWaitingFromUpgrade(userId, req.triageTaskId());
        } else {
            // 直连问诊：带用户自填病例（症状 + 私密桶图 key），无则为空（兼容旧客户端）。
            result = service.createWaiting(userId, ConsultSource.DIRECT,
                    req == null ? null : req.symptomText(),
                    req == null ? null : req.imageObjectKeys());
        }
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

    /**
     * 用户查看自己提交的病例（症状 + 私密图签名 URL）。会话页顶部摘要条「View」展开用。
     * 归属经 {@link ConsultSessionService#getForUser} 校验（非本人 → 404，不泄露他人会话）。
     */
    @GetMapping("/{id}/case")
    public ConsultAiContextResponse caseContext(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long userId = currentUserId(jwt);
        service.getForUser(userId, id); // 归属校验：非本人即抛 notFound
        return aiContextService.forSession(id);
    }

    /**
     * 用户查看本次会诊最终诊断（Story C 收尾）。兽医结束会话时定格于 {@code consult_sessions.vet_diagnosis}；
     * 结束后(含 30min 续聊期 / CLOSED)仍可查，作为用户侧「查看会诊结果」入口的数据源。
     * 归属经 {@link ConsultSessionService#getForUser} 校验（非本人 → 404）；未出诊断 → 204。
     * <p>诊断为健康数据：仅按需返回，绝不进日志（访问日志层已对 {@code diagnosis} 字段脱敏）。
     */
    @GetMapping("/{id}/diagnosis")
    public org.springframework.http.ResponseEntity<com.tailtopia.consult.domain.VetDiagnosis> diagnosis(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        var d = service.getForUser(currentUserId(jwt), id).getVetDiagnosis();
        return d == null
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(d);
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

    /**
     * 封禁挂起逃生（Story 3.8，H-5）：用户对挂起会话主动「立即结束」→ 强制结束 + 按支付方式退款（不等 15min）。
     * 仅本人 + 挂起态可逃生（否则 404 防枚举）。返回结束后会话视图（INTERRUPTED 终态）。
     */
    @PostMapping("/{id}/escape")
    public ConsultSessionResponse escape(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long userId = currentUserId(jwt);
        suspensionService.escapeByUser(userId, id);
        return ConsultSessionResponse.of(service.getForUser(userId, id),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, false);
    }

    // ===== Story 5.6：评分门（用户侧）=====

    /** 提交评分（1-5 星必填 + ≤100 字选填）→ PENDING_CLOSE→CLOSED(RATED) + 存档；或补弹后补记。 */
    @PostMapping("/{id}/rating")
    public ConsultSessionResponse rate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody SubmitRatingRequest req) {
        return ConsultSessionResponse.of(
                closeService.submitRating(currentUserId(jwt), id, req.stars(), req.comment()),
                ConsultSessionService.WAITING_TIMEOUT_SECONDS, false);
    }

    /** 补弹已展示 → 置 PROMPTED（不再弹）。 */
    @PatchMapping("/{id}/rating-prompted")
    public void ratingPrompted(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        closeService.markPrompted(currentUserId(jwt), id);
    }

    /** 待补弹评分的已关闭会话（无则 204）。进问诊页补弹一次（Story 5.8）。 */
    @GetMapping("/pending-rating")
    public org.springframework.http.ResponseEntity<ConsultSessionResponse> pendingRating(
            @AuthenticationPrincipal Jwt jwt) {
        return closeService.pendingRating(currentUserId(jwt))
                .map(s -> org.springframework.http.ResponseEntity.ok(
                        ConsultSessionResponse.of(s, ConsultSessionService.WAITING_TIMEOUT_SECONDS, false)))
                .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
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

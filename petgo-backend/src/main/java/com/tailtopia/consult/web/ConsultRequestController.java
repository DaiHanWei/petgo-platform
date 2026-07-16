package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultRequestStatusResponse;
import com.tailtopia.consult.dto.ConsultationResponse;
import com.tailtopia.consult.dto.CreateConsultationRequest;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免费入队端点（Story 3.2，Epic 3 计费流入口）。{@code POST /api/v1/consultations}（JWT USER）：
 * 免费发起咨询 → {@code consult_requests}(QUEUEING) <b>不扣费不建订单</b> + 广播在线兽医（FR-22E）。
 *
 * <p>宠物档案一人一宠（{@code PetProfileRepository.findByOwnerId}），故 pet_profile 从 owner 派生、不收客户端传
 * （用户身份取自 JWT，不信任客户端）。接单在 3-3、限时支付建单在 3-4。
 *
 * <p><b>V84 起 body 带病例</b>（Story 3.2 [OPEN] 收口 + D1/D2）：兽医接单前据病例判断是否接单，故入队即落病例。
 * 用户发起路径已由本端点收口——V1.0 的「直接建 {@code consult_sessions}(WAITING) 等兽医」发起路径不再有新入口
 * （{@code consult_sessions} 仍是付款成功后的会话载体，见 {@code ConsultPayService}）。
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultRequestController {

    private final ConsultRequestService service;

    public ConsultRequestController(ConsultRequestService service) {
        this.service = service;
    }

    /**
     * 发起入队（Story 3.2 + [OPEN] 收口 D1/D2）：body 带病例——{@code DIRECT} 自填症状/私密图 key，
     * 或 {@code AI_UPGRADE} + {@code triageTaskId}（AI 上下文后端从 triage 拉，前端不重传）。
     * body 可省（兼容无病例发起）。RED 兜底拒绝在 service（红色态零兽医引流）。
     */
    @PostMapping
    public ConsultationResponse create(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) CreateConsultationRequest body) {
        return ConsultationResponse.of(service.createRequest(currentUserId(jwt), body));
    }

    /**
     * 请求状态轮询（Story 3.5）：前端下单三屏据此驱动 待接单→待支付 跃迁 + 服务端权威倒计时。
     * 仅本人；请求已消失（超时删/转单删）或非本人 → 404（前端据 404 + {@code GET /consult-sessions/active}
     * 区分「已转单进会话」vs「超时无兽医」）。
     */
    @GetMapping("/{requestToken}")
    public ConsultRequestStatusResponse status(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String requestToken) {
        return ConsultRequestStatusResponse.of(service.statusOf(currentUserId(jwt), requestToken));
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

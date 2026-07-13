package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultationResponse;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免费入队端点（Story 3.2，Epic 3 计费流入口）。{@code POST /api/v1/consultations}（JWT USER）：
 * 免费发起咨询 → {@code consult_requests}(QUEUEING) <b>不扣费不建订单</b> + 广播在线兽医（FR-22E）。
 *
 * <p>宠物档案一人一宠（{@code PetProfileRepository.findByOwnerId}），故 pet_profile 从 owner 派生、无需请求体
 * （用户身份取自 JWT，不信任客户端）。接单在 3-3、限时支付建单在 3-4。与 V1.0 {@code /consult-sessions} 免费流并存。
 */
@RestController
@RequestMapping("/api/v1/consultations")
public class ConsultRequestController {

    private final ConsultRequestService service;

    public ConsultRequestController(ConsultRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ConsultationResponse create(@AuthenticationPrincipal Jwt jwt) {
        return ConsultationResponse.of(service.createRequest(currentUserId(jwt)));
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

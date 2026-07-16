package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultAcceptResponse;
import com.tailtopia.consult.dto.ConsultAiContextResponse;
import com.tailtopia.consult.dto.VetQueueResponse;
import com.tailtopia.consult.service.ConsultRequestService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医侧计费流接单端点（Story 3.3，{@code hasRole('VET')} 由 SecurityConfig {@code /api/v1/vet/**} 门控）。
 * 接 {@code consult_requests}（计费流），与 V1.0 {@code /api/v1/vet/consult-sessions/{id}/accept}
 * （{@code ConsultAcceptService}，免费直连流 consult_sessions WAITING→IN_PROGRESS 即建 IM 会话）<b>并存不混用</b>。
 *
 * <ul>
 *   <li>{@code GET /api/v1/vet/consultations/queue}：兽医计费队列（Story 3.6）——本人「等待支付」中间态
 *       （FR-53A）+ 可接单 QUEUEING 池（忙则空）。工作台待接单 Tab 轮询。</li>
 *   <li>{@code POST /api/v1/vet/consultations/{requestToken}/accept}：接单（CAS QUEUEING→ACCEPTED_AWAIT_PAY
 *       + 开 1.5min 支付窗 + goBusy 占用）。<b>接单不建会话/订单</b>——会话/订单在 3-4 支付成功才建。</li>
 * </ul>
 *
 * <p>token 寻址（不可枚举）；vetId 取自 JWT（不信客户端）。不存在 token 与「已被接单」返同一 409（防枚举）。
 */
@RestController
@RequestMapping("/api/v1/vet/consultations")
public class VetConsultRequestController {

    private final ConsultRequestService service;

    public VetConsultRequestController(ConsultRequestService service) {
        this.service = service;
    }

    /** 兽医计费队列（Story 3.6）：本人待支付中间态 + 可接单 QUEUEING 池（忙则空）。 */
    @GetMapping("/queue")
    public VetQueueResponse queue(@AuthenticationPrincipal Jwt jwt) {
        return service.vetQueue(currentVetId(jwt));
    }

    /**
     * 请求病例（D1，2026-07-16）：兽医<b>接单前</b>展开看完整症状 + 私密图（现签短 TTL URL）。
     * token 寻址（不可枚举）；不存在/已结束 → 404（防枚举）。无病例 → {@code hasAiContext=false}。
     */
    @GetMapping("/{requestToken}/case")
    public ConsultAiContextResponse requestCase(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String requestToken) {
        currentVetId(jwt); // 触发 JWT 校验（队列池对所有在线兽医开放，接单前无 vet 归属可校验）
        return service.caseOf(requestToken);
    }

    @PostMapping("/{requestToken}/accept")
    public ConsultAcceptResponse accept(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String requestToken) {
        return ConsultAcceptResponse.of(service.acceptRequest(currentVetId(jwt), requestToken));
    }

    private static long currentVetId(Jwt jwt) {
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

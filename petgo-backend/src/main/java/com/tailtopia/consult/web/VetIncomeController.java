package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.VetIncomeResponse;
import com.tailtopia.consult.service.VetSettlementService;
import com.tailtopia.shared.error.AppException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医收入端点（Story 3.7，{@code hasRole('VET')} 由 SecurityConfig {@code /api/v1/vet/**} 门控）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/vet/income}：当月待结算实时聚合 + 历史月结倒序（FR-53C/53D）。</li>
 * </ul>
 * {@code vetId} 取自 JWT（不信客户端）。
 */
@RestController
@RequestMapping("/api/v1/vet/income")
public class VetIncomeController {

    private final VetSettlementService service;

    public VetIncomeController(VetSettlementService service) {
        this.service = service;
    }

    @GetMapping
    public VetIncomeResponse income(@AuthenticationPrincipal Jwt jwt) {
        return service.income(currentVetId(jwt));
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

package com.tailtopia.consult.web;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.consult.dto.ConsultPricingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧兽医咨询定价查询（bug 20260729-417）。
 *
 * <p>{@code GET /api/v1/consult/pricing} → {@code {price: 50000}}。
 * 门控 {@code hasRole('USER')}（SecurityConfig {@code /api/v1/consult/**} 统一收口）。
 * 价格实时读 {@code pricing_config} 单行，与 {@link com.tailtopia.consult.service.ConsultPayService}
 * 扣费读的是同一来源——后台改价后前端展示随之更新，不再依赖客户端硬编码。
 */
@RestController
@RequestMapping("/api/v1/consult")
public class ConsultPricingController {

    private final PlatformConfigService platformConfig;

    public ConsultPricingController(PlatformConfigService platformConfig) {
        this.platformConfig = platformConfig;
    }

    @GetMapping("/pricing")
    public ConsultPricingResponse pricing() {
        return new ConsultPricingResponse(platformConfig.pricing().getVetConsultPrice());
    }
}

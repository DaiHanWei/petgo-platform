package com.petgo.consult.web;

import com.petgo.consult.dto.ConsultAvailabilityResponse;
import com.petgo.consult.service.ConsultAvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧兽医咨询入口可用性（Story 5.2，AC3）。
 *
 * <p>{@code GET /api/v1/consult/availability} → {@code {vetOnline: bool}}。
 * 门控 {@code hasRole('USER')}（与 5.3 发起入口对齐）。绝不透传在线人数。
 */
@RestController
@RequestMapping("/api/v1/consult")
public class ConsultAvailabilityController {

    private final ConsultAvailabilityService availabilityService;

    public ConsultAvailabilityController(ConsultAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/availability")
    public ConsultAvailabilityResponse availability() {
        return availabilityService.availability();
    }
}

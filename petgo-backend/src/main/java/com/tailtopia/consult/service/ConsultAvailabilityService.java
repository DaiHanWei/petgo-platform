package com.tailtopia.consult.service;

import com.tailtopia.consult.dto.ConsultAvailabilityResponse;
import com.tailtopia.vet.service.VetPresenceService;
import org.springframework.stereotype.Service;

/**
 * 用户侧兽医咨询可用性查询（Story 5.2）。
 *
 * <p>模块边界：经 {@link VetPresenceService} 接口读「是否有兽医在线」，
 * <b>不直访 vet 的 Redis 键名/repository</b>（架构：模块间经 service）。
 * 对用户侧只透传 bool，精确人数即便内部用于匹配也不外泄。
 */
@Service
public class ConsultAvailabilityService {

    private final VetPresenceService presence;

    public ConsultAvailabilityService(VetPresenceService presence) {
        this.presence = presence;
    }

    public ConsultAvailabilityResponse availability() {
        return new ConsultAvailabilityResponse(
                presence.anyOnline(), ConsultAvailabilityResponse.DEFAULT_WINDOW_KEY);
    }
}

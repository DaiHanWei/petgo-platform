package com.tailtopia.vet.web;

import com.tailtopia.vet.dto.OnlineStatusRequest;
import com.tailtopia.vet.dto.OnlineStatusResponse;
import com.tailtopia.vet.service.VetPresenceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医在线态端点（Story 5.2，{@code hasRole('VET')}）。
 *
 * <ul>
 *   <li>{@code PUT /api/v1/vet/online-status}：切在线/离线（写 Redis 在线集合）。</li>
 *   <li>{@code GET /api/v1/vet/online-status}：读自身当前在线态（工作台开关初值）。</li>
 *   <li>{@code POST /api/v1/vet/heartbeat}：前台心跳续期 TTL（防幽灵在线靠 TTL 兜底）。</li>
 *   <li>{@code POST /api/v1/vet/logout}：登出即离线（不再被计为可接单）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/vet")
public class VetPresenceController {

    private final VetPresenceService presence;

    public VetPresenceController(VetPresenceService presence) {
        this.presence = presence;
    }

    @PutMapping("/online-status")
    public OnlineStatusResponse setOnline(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OnlineStatusRequest req) {
        long vetId = VetMeController.currentVetId(jwt);
        if (Boolean.TRUE.equals(req.online())) {
            presence.goOnline(vetId);
        } else {
            presence.goOffline(vetId);
        }
        return OnlineStatusResponse.of(presence.statusOf(vetId));
    }

    @GetMapping("/online-status")
    public OnlineStatusResponse getOnline(@AuthenticationPrincipal Jwt jwt) {
        return OnlineStatusResponse.of(presence.statusOf(VetMeController.currentVetId(jwt)));
    }

    @PostMapping("/heartbeat")
    public OnlineStatusResponse heartbeat(@AuthenticationPrincipal Jwt jwt) {
        long vetId = VetMeController.currentVetId(jwt);
        presence.heartbeat(vetId);
        return OnlineStatusResponse.of(presence.statusOf(vetId));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        presence.goOffline(VetMeController.currentVetId(jwt));
        return ResponseEntity.noContent().build();
    }
}

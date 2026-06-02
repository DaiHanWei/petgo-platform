package com.petgo.consult.web;

import com.petgo.consult.dto.ConsultAssistResponse;
import com.petgo.consult.dto.VetInboxItem;
import com.petgo.consult.dto.VetSessionView;
import com.petgo.consult.service.ConsultAcceptService;
import com.petgo.consult.service.ConsultCloseService;
import com.petgo.consult.service.VetConsultService;
import com.petgo.shared.error.AppException;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医侧咨询端点（Story 5.5，{@code hasRole('VET')}，落 vet 前缀）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/vet/consult-sessions/waiting}：待接单列表（含 AI 上下文摘要）。</li>
 *   <li>{@code POST /api/v1/vet/consult-sessions/{id}/accept}：接单（CAS WAITING→IN_PROGRESS + IM 建会话）。</li>
 *   <li>{@code GET /api/v1/vet/consult-sessions/{id}}：进行中会话视图（含 im_conversation_id）。</li>
 *   <li>{@code GET /api/v1/vet/consult-sessions/{id}/assist}：FR-5 辅助（AI 参考回复 + 冷启动空历史）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/vet/consult-sessions")
public class VetConsultController {

    private final VetConsultService vetConsultService;
    private final ConsultAcceptService acceptService;
    private final ConsultCloseService closeService;

    public VetConsultController(VetConsultService vetConsultService, ConsultAcceptService acceptService,
            ConsultCloseService closeService) {
        this.vetConsultService = vetConsultService;
        this.acceptService = acceptService;
        this.closeService = closeService;
    }

    @GetMapping("/waiting")
    public List<VetInboxItem> waiting() {
        return vetConsultService.waitingList();
    }

    @PostMapping("/{id}/accept")
    public VetSessionView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return VetSessionView.of(acceptService.accept(currentVetId(jwt), id));
    }

    /** 兽医结束会话（二次确认在前端）：IN_PROGRESS → PENDING_CLOSE（Story 5.6）。 */
    @PostMapping("/{id}/end")
    public VetSessionView end(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return VetSessionView.of(closeService.endByVet(currentVetId(jwt), id));
    }

    /** 兽医回复后通知用户（Story 6.2，FR-22A）：发完 IM 消息后 ping → 推送用户「有新回复」。 */
    @PostMapping("/{id}/notify-reply")
    public void notifyReply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        vetConsultService.notifyReply(currentVetId(jwt), id);
    }

    @GetMapping("/{id}")
    public VetSessionView session(@PathVariable long id) {
        return vetConsultService.sessionView(id);
    }

    @GetMapping("/{id}/assist")
    public ConsultAssistResponse assist(@PathVariable long id) {
        return vetConsultService.assist(id);
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

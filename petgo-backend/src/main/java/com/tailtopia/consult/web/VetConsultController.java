package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultAssistResponse;
import com.tailtopia.consult.dto.VetActiveItem;
import com.tailtopia.consult.dto.VetEndRequest;
import com.tailtopia.consult.dto.VetHistoryItem;
import com.tailtopia.consult.dto.VetInboxItem;
import com.tailtopia.consult.dto.VetSessionView;
import com.tailtopia.consult.service.ConsultAcceptService;
import com.tailtopia.consult.service.ConsultCloseService;
import com.tailtopia.consult.service.VetConsultService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医侧咨询端点（Story 5.5，{@code hasRole('VET')}，落 vet 前缀）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/vet/consult-sessions/waiting}：待接单列表（含 AI 上下文摘要 + 宠物身份）。</li>
 *   <li>{@code GET /api/v1/vet/consult-sessions/in-progress}：工作台「进行中」Tab 活跃会话列表。</li>
 *   <li>{@code GET /api/v1/vet/consult-sessions/history}：工作台「历史」Tab 终态会话 + 评分列表。</li>
 *   <li>{@code POST /api/v1/vet/consult-sessions/{id}/accept}：接单（CAS WAITING→IN_PROGRESS + IM 建会话）。</li>
 *   <li>{@code GET /api/v1/vet/consult-sessions/{id}}：进行中会话视图（含 im_conversation_id + 宠物身份）。</li>
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

    /** 工作台「进行中」Tab：当前兽医活跃态会话列表（IN_PROGRESS/PENDING_CLOSE）。 */
    @GetMapping("/in-progress")
    public List<VetActiveItem> inProgress(@AuthenticationPrincipal Jwt jwt) {
        return vetConsultService.inProgressList(currentVetId(jwt));
    }

    /** 工作台「历史」Tab：当前兽医终态会话 + 用户评分摘要，时间倒序。 */
    @GetMapping("/history")
    public List<VetHistoryItem> history(@AuthenticationPrincipal Jwt jwt) {
        return vetConsultService.historyList(currentVetId(jwt));
    }

    // 写路径（接单/结束/退单）返回不富化的基础视图：CAS 写事务已提交，不再挂跨模块身份富化
    // （否则富化查询失败会把「已成功的写」翻成 500 → 幽灵接单态分歧）。前端接单后跳会话页，
    // 经 GET /{id}（sessionView）单独拉富化顶栏，故写响应无需 petName 等字段。

    @PostMapping("/{id}/accept")
    public VetSessionView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return VetSessionView.of(acceptService.accept(currentVetId(jwt), id));
    }

    /**
     * 兽医结束会话（二次确认在前端）：IN_PROGRESS → PENDING_CLOSE（Story 5.6）。
     * Story C：必须随结束提交最终诊断（{@code diagnosis} 必填，空 → 422）；诊断定格存档 + 推用户。
     */
    @PostMapping("/{id}/end")
    public VetSessionView end(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody VetEndRequest req) {
        return VetSessionView.of(closeService.endByVet(currentVetId(jwt), id, req.toDiagnosis()));
    }

    /** 兽医退单（Story 5.3 R2，F11）：IN_PROGRESS → WAITING，重新入队广播。仅本会话接单兽医可退单。 */
    @PostMapping("/{id}/release")
    public VetSessionView release(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return VetSessionView.of(acceptService.release(currentVetId(jwt), id));
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

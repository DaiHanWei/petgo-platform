package com.tailtopia.consult.web;

import com.tailtopia.consult.dto.ConsultAiContextResponse;
import com.tailtopia.consult.service.ConsultAiContextService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兽医侧会话 AI 上下文读取（Story 5.4，AC2）。
 *
 * <p>{@code GET /api/v1/vet/consult-sessions/{id}/ai-context}（{@code hasRole('VET')}，落在 vet 前缀下）。
 * 供待接单预览 + 接单后顶部上下文卡。DIRECT 会话回 {@code hasAiContext=false}。
 */
@RestController
@RequestMapping("/api/v1/vet/consult-sessions")
public class VetConsultContextController {

    private final ConsultAiContextService aiContextService;

    public VetConsultContextController(ConsultAiContextService aiContextService) {
        this.aiContextService = aiContextService;
    }

    @GetMapping("/{id}/ai-context")
    public ConsultAiContextResponse aiContext(@PathVariable long id) {
        return aiContextService.forSession(id);
    }
}

package com.tailtopia.consult.service;

import com.tailtopia.consult.domain.ConsultSession;
import com.tailtopia.consult.dto.ConsultAiContextResponse;
import com.tailtopia.consult.repository.ConsultSessionRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话 AI 上下文读取（Story 5.4，兽医侧）。
 *
 * <p>DIRECT 会话 → 返回 {@link ConsultAiContextResponse#empty()}（前端不渲染上下文卡）。
 * AI_UPGRADE 会话 → 返回评级/描述 + 私密图<b>现签短 TTL 签名 URL</b>（经 {@link SignedUrlService}，
 * 绝不入库/落日志）。待接单可见范围（哪些 vet 可读）由 Story 5.5 接单逻辑细化，本故事先放 VET 可读。
 */
@Service
public class ConsultAiContextService {

    private final ConsultSessionRepository repo;
    private final SignedUrlService signedUrlService;

    public ConsultAiContextService(ConsultSessionRepository repo, SignedUrlService signedUrlService) {
        this.repo = repo;
        this.signedUrlService = signedUrlService;
    }

    @Transactional(readOnly = true)
    public ConsultAiContextResponse forSession(long sessionId) {
        ConsultSession s = repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
        // Story F：直连自填病例也要展示给兽医，故按「有病例」判（AI 上下文 或 直连症状/图）。
        if (!s.hasCase()) {
            return ConsultAiContextResponse.empty();
        }
        List<String> refs = s.getAiImageRefs();
        List<String> urls = (refs == null || refs.isEmpty()) ? List.of() : signedUrlService.signAll(refs);
        // dangerLevel 对直连为 null（无 AI 评级），前端据此显示「病例」而非「AI 上下文」标题。
        return new ConsultAiContextResponse(true, s.getAiDangerLevel(), s.getAiSymptomText(), urls);
    }
}

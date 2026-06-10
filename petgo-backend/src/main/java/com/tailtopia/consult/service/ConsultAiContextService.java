package com.petgo.consult.service;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.dto.ConsultAiContextResponse;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.media.SignedUrlService;
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
        if (!s.hasAiContext()) {
            return ConsultAiContextResponse.empty();
        }
        List<String> refs = s.getAiImageRefs();
        List<String> urls = (refs == null || refs.isEmpty()) ? List.of() : signedUrlService.signAll(refs);
        return new ConsultAiContextResponse(true, s.getAiDangerLevel(), s.getAiSymptomText(), urls);
    }
}

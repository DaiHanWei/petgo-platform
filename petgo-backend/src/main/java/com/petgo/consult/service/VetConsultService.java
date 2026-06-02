package com.petgo.consult.service;

import com.petgo.consult.domain.ConsultSession;
import com.petgo.consult.dto.ConsultAssistResponse;
import com.petgo.consult.dto.VetInboxItem;
import com.petgo.consult.dto.VetSessionView;
import com.petgo.consult.repository.ConsultSessionRepository;
import com.petgo.shared.error.AppException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兽医侧咨询读取 + FR-5 辅助（Story 5.5）。待接单列表 / 会话视图 / AI 参考回复（冷启动历史空）。
 */
@Service
public class VetConsultService {

    private final ConsultSessionRepository repo;
    private final ConsultQueueService queue;

    public VetConsultService(ConsultSessionRepository repo, ConsultQueueService queue) {
        this.repo = repo;
        this.queue = queue;
    }

    /** 待接单列表：按 Redis 队列 FIFO 取 WAITING 会话，携 AI 上下文摘要。 */
    @Transactional(readOnly = true)
    public List<VetInboxItem> waitingList() {
        return queue.waitingSessionIds().stream()
                .map(repo::findById)
                .flatMap(java.util.Optional::stream)
                .filter(s -> s.getStatus() == com.petgo.consult.domain.SessionStatus.WAITING)
                .map(VetInboxItem::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public VetSessionView sessionView(long sessionId) {
        return VetSessionView.of(load(sessionId));
    }

    /**
     * FR-5 辅助工具。AI 参考回复基于会话 AI 上下文生成（仅供参考，不自动发用户）；
     * 历史摘要冷启动空（G-2，返回空列表）。
     *
     * <p>说明：V1 冷启动用基于上下文的模板化参考回复（确定性，免外部凭证可验）；
     * 接真实 Gemini 生成更自然的参考回复属 L2 增强（{@code GeminiClient}，需 key）。
     */
    @Transactional(readOnly = true)
    public ConsultAssistResponse assist(long sessionId) {
        ConsultSession s = load(sessionId);
        return new ConsultAssistResponse(buildReferenceReply(s), List.of());
    }

    private String buildReferenceReply(ConsultSession s) {
        StringBuilder sb = new StringBuilder("您好，我已了解您的情况");
        if (s.hasAiContext()) {
            if ("YELLOW".equals(s.getAiDangerLevel())) {
                sb.append("，AI 初判需密切观察");
            }
            if (s.getAiSymptomText() != null && !s.getAiSymptomText().isBlank()) {
                sb.append("（症状：").append(s.getAiSymptomText()).append("）");
            }
        }
        sb.append("。请问最近的精神、进食和排便情况如何？方便的话再补充一张清晰照片。");
        return sb.toString();
    }

    private ConsultSession load(long sessionId) {
        return repo.findById(sessionId)
                .orElseThrow(() -> AppException.notFound("咨询不存在"));
    }
}

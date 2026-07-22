package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.domain.HealthEvent;
import com.tailtopia.profile.dto.ArchiveDecisionRequest;
import com.tailtopia.profile.dto.ArchiveDecisionResponse;
import com.tailtopia.profile.event.HealthArchivedEvent;
import com.tailtopia.profile.repository.HealthEventRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.ImToOssArchiver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 问诊存档决策服务（Story 2.5）。一次性幂等（按 {@code source_ref}）：
 * 已决策则忽略（FR-16「只问一次」）；ARCHIVED 建展示条目（含 IM 图复制到私密桶），SKIPPED 仅落决策。
 *
 * <p>护栏：症状/建议/评级属健康数据，**不进日志**；{@code petId} 归属经 {@link ProfileService} 校验。
 */
@Service
public class HealthEventService {

    private final HealthEventRepository healthEvents;
    private final ProfileService profileService;
    private final ImToOssArchiver imToOssArchiver;
    private final ApplicationEventPublisher events;

    public HealthEventService(HealthEventRepository healthEvents, ProfileService profileService,
            ImToOssArchiver imToOssArchiver, ApplicationEventPublisher events) {
        this.healthEvents = healthEvents;
        this.profileService = profileService;
        this.imToOssArchiver = imToOssArchiver;
        this.events = events;
    }

    /** 该 sourceRef 是否已决策（供 Epic4/5 触发端判断是否还需弹窗）。 */
    @Transactional(readOnly = true)
    public boolean hasDecision(String sourceRef) {
        return healthEvents.existsBySourceRef(sourceRef);
    }

    /** 该 sourceRef 是否【已存档】(ARCHIVED)：问诊结果页据此隐藏保存按钮（bug 20260721-333）。 */
    @Transactional(readOnly = true)
    public boolean isArchived(String sourceRef) {
        return healthEvents.existsBySourceRefAndArchiveDecision(sourceRef, ArchiveDecision.ARCHIVED);
    }

    @Transactional
    public ArchiveDecisionResponse recordDecision(long ownerId, ArchiveDecisionRequest req) {
        // 归属校验：petId 必须属当前用户（防越权写他人档案）。
        if (!profileService.ownsPet(ownerId, req.petId())) {
            throw AppException.forbidden("无法操作该宠物档案");
        }
        // 幂等：已决策 → 直接返回既有决策，不重复弹/重复存。
        var existing = healthEvents.findBySourceRef(req.sourceRef());
        if (existing.isPresent()) {
            return new ArchiveDecisionResponse(req.sourceRef(), existing.get().getArchiveDecision(), true);
        }

        LocalDate eventDate = req.eventDate() == null ? LocalDate.now() : req.eventDate();
        HealthEvent event;
        if (req.decision() == ArchiveDecision.ARCHIVED) {
            // IM 聊天图复制到私密桶②，存自有 key（绝不存 IM URL）。AI 分诊图已在私密桶，直接引用其 key。
            List<String> imageKeys = imToOssArchiver.archiveImImagesToPrivate(req.petId(), req.imImageRefs());
            event = HealthEvent.archived(req.petId(), req.sourceType(), req.sourceRef(), eventDate,
                    blankToNull(req.symptomSummary()), blankToNull(req.aiLevel()),
                    blankToNull(req.adviceSummary()), imageKeys.isEmpty() ? null : imageKeys);
        } else {
            event = HealthEvent.skipped(req.petId(), req.sourceType(), req.sourceRef(), eventDate);
        }

        try {
            healthEvents.save(event);
        } catch (DataIntegrityViolationException e) {
            // 并发同 sourceRef：唯一约束兜底，归一为幂等已决策。
            return new ArchiveDecisionResponse(req.sourceRef(), req.decision(), true);
        }
        // 里程碑 C-S4「第一次保存兽医问诊结论」自动完成（Story 8.3，仅首次存档触发，幂等）。
        if (req.decision() == ArchiveDecision.ARCHIVED) {
            events.publishEvent(new HealthArchivedEvent(ownerId, req.petId(), Instant.now()));
        }
        return new ArchiveDecisionResponse(req.sourceRef(), req.decision(), false);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

package com.petgo.profile.service;

import com.petgo.profile.domain.ArchiveDecision;
import com.petgo.profile.repository.HealthEventRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * {@link HealthEventTimelineSource} 实现（Story 2.5 提供 bean，供 2.4 {@link TimelineService} 聚合）。
 *
 * <p>只取已 ARCHIVED 的健康事件（SKIPPED 不展示）。经 {@link ProfileService} 解析当前用户的宠物，
 * 不让 timeline 直读 health_events 表的归属（边界经 service）。健康摘要/评级不进日志。
 */
@Component
public class HealthEventTimelineSourceImpl implements HealthEventTimelineSource {

    private final HealthEventRepository healthEvents;
    private final ProfileService profileService;

    public HealthEventTimelineSourceImpl(HealthEventRepository healthEvents, ProfileService profileService) {
        this.healthEvents = healthEvents;
        this.profileService = profileService;
    }

    @Override
    public List<HealthEventView> recentHealthEvents(long ownerId, Instant before, int limit) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents
                        .findByPetIdAndArchiveDecisionAndCreatedAtLessThanOrderByCreatedAtDesc(
                                pet.getId(), ArchiveDecision.ARCHIVED,
                                before == null ? Instant.now() : before, PageRequest.of(0, limit))
                        .stream()
                        .map(e -> new HealthEventView(e.getCreatedAt(), e.getAiLevel(), e.getSymptomSummary()))
                        .toList())
                .orElse(List.of());
    }
}

package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.repository.HealthEventRepository;
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
                        .map(HealthEventTimelineSourceImpl::toView)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<HealthEventView> healthEventsInRange(long ownerId, Instant from, Instant to) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents
                        .findByPetIdAndArchiveDecisionAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                                pet.getId(), ArchiveDecision.ARCHIVED, from, to)
                        .stream()
                        .map(HealthEventTimelineSourceImpl::toView)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<HealthEventView> healthEventsOnDay(long ownerId, Instant dayStart, Instant dayEnd) {
        return healthEventsInRange(ownerId, dayStart, dayEnd);
    }

    @Override
    public long countHealthEvents(long ownerId) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents.countByPetIdAndArchiveDecision(pet.getId(), ArchiveDecision.ARCHIVED))
                .orElse(0L);
    }

    private static HealthEventView toView(com.tailtopia.profile.domain.HealthEvent e) {
        return new HealthEventView(e.getCreatedAt(), e.getAiLevel(), e.getSymptomSummary(),
                e.getSourceType() == null ? null : e.getSourceType().name());
    }
}

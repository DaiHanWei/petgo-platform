package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.ArchiveDecision;
import com.tailtopia.profile.repository.HealthEventRepository;
import java.time.Instant;
import java.time.LocalDate;
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
    public List<HealthEventView> recentHealthEvents(long ownerId, LocalDate anchorDate,
            Instant anchorKey, int limit) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents
                        .findBeforeAnchor(pet.getId(), ArchiveDecision.ARCHIVED,
                                anchorDate, anchorKey, PageRequest.of(0, limit))
                        .stream()
                        .map(HealthEventTimelineSourceImpl::toView)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<HealthEventView> healthEventsInRange(long ownerId, LocalDate from, LocalDate to) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents
                        .findByEventDateBetween(pet.getId(), ArchiveDecision.ARCHIVED, from, to)
                        .stream()
                        .map(HealthEventTimelineSourceImpl::toView)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<HealthEventView> healthEventsOnDay(long ownerId, LocalDate day) {
        return healthEventsInRange(ownerId, day, day);
    }

    @Override
    public long countHealthEvents(long ownerId) {
        return profileService.findByOwnerId(ownerId)
                .map(pet -> healthEvents.countByPetIdAndArchiveDecision(pet.getId(), ArchiveDecision.ARCHIVED))
                .orElse(0L);
    }

    private static HealthEventView toView(com.tailtopia.profile.domain.HealthEvent e) {
        return new HealthEventView(e.getCreatedAt(), e.getEventDate(), e.getAiLevel(), e.getSymptomSummary(),
                e.getSourceType() == null ? null : e.getSourceType().name(), e.getSourceRef());
    }
}

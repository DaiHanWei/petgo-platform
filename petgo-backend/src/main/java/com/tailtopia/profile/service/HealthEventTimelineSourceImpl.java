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
 * <p>只取已 ARCHIVED 的健康事件（SKIPPED 不展示）。V1.4.0 multi-pet：直接按 {@code petId} 查
 * （归属由调用方经 {@link PetResolver} 校验过）。健康摘要/评级不进日志。
 */
@Component
public class HealthEventTimelineSourceImpl implements HealthEventTimelineSource {

    private final HealthEventRepository healthEvents;

    public HealthEventTimelineSourceImpl(HealthEventRepository healthEvents) {
        this.healthEvents = healthEvents;
    }

    @Override
    public List<HealthEventView> recentHealthEvents(long petId, LocalDate anchorDate,
            Instant anchorKey, int limit) {
        return healthEvents
                .findBeforeAnchor(petId, ArchiveDecision.ARCHIVED, anchorDate, anchorKey, PageRequest.of(0, limit))
                .stream()
                .map(HealthEventTimelineSourceImpl::toView)
                .toList();
    }

    @Override
    public List<HealthEventView> healthEventsInRange(long petId, LocalDate from, LocalDate to) {
        return healthEvents
                .findByEventDateBetween(petId, ArchiveDecision.ARCHIVED, from, to)
                .stream()
                .map(HealthEventTimelineSourceImpl::toView)
                .toList();
    }

    @Override
    public List<HealthEventView> healthEventsOnDay(long petId, LocalDate day) {
        return healthEventsInRange(petId, day, day);
    }

    @Override
    public long countHealthEvents(long petId) {
        return healthEvents.countByPetIdAndArchiveDecision(petId, ArchiveDecision.ARCHIVED);
    }

    private static HealthEventView toView(com.tailtopia.profile.domain.HealthEvent e) {
        return new HealthEventView(e.getCreatedAt(), e.getEventDate(), e.getAiLevel(), e.getSymptomSummary(),
                e.getSourceType() == null ? null : e.getSourceType().name(), e.getSourceRef());
    }
}

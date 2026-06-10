package com.petgo.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.profile.domain.ArchiveDecision;
import com.petgo.profile.domain.HealthEvent;
import com.petgo.profile.domain.HealthSourceType;
import com.petgo.profile.dto.ArchiveDecisionRequest;
import com.petgo.profile.dto.ArchiveDecisionResponse;
import com.petgo.profile.repository.HealthEventRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.media.ImToOssArchiver;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0：存档决策幂等 + 归属校验 + ARCHIVED 复制 IM 图（AC1/AC2 逻辑面）。 */
class HealthEventServiceTest {

    private HealthEventRepository repo;
    private ProfileService profileService;
    private ImToOssArchiver archiver;
    private HealthEventService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(HealthEventRepository.class);
        profileService = Mockito.mock(ProfileService.class);
        archiver = Mockito.mock(ImToOssArchiver.class);
        when(profileService.ownsPet(1L, 5L)).thenReturn(true);
        when(archiver.archiveImImagesToPrivate(anyLong(), anyList())).thenReturn(List.of());
        service = new HealthEventService(repo, profileService, archiver,
                Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private ArchiveDecisionRequest archivedReq() {
        return new ArchiveDecisionRequest(HealthSourceType.AI_TRIAGE, "ref-1", 5L, ArchiveDecision.ARCHIVED,
                LocalDate.of(2026, 6, 2), "咳嗽两天", "YELLOW", "观察 24h", null);
    }

    @Test
    void unownedPetRejected() {
        when(profileService.ownsPet(1L, 9L)).thenReturn(false);
        var req = new ArchiveDecisionRequest(HealthSourceType.AI_TRIAGE, "r", 9L, ArchiveDecision.SKIPPED,
                null, null, null, null, null);
        assertThatThrownBy(() -> service.recordDecision(1L, req)).isInstanceOf(AppException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void archivedPersistsAndCopiesImImages() {
        when(repo.findBySourceRef("ref-1")).thenReturn(Optional.empty());
        when(archiver.archiveImImagesToPrivate(5L, null)).thenReturn(List.of("private/health/5/k.jpg"));
        when(repo.save(any(HealthEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        ArchiveDecisionResponse resp = service.recordDecision(1L, archivedReq());

        assertThat(resp.alreadyDecided()).isFalse();
        assertThat(resp.decision()).isEqualTo(ArchiveDecision.ARCHIVED);
        verify(archiver).archiveImImagesToPrivate(5L, null);
        verify(repo).save(any(HealthEvent.class));
    }

    @Test
    void alreadyDecidedIsIdempotentNoSave() {
        HealthEvent existing = HealthEvent.skipped(5L, HealthSourceType.AI_TRIAGE, "ref-1", LocalDate.now());
        when(repo.findBySourceRef("ref-1")).thenReturn(Optional.of(existing));

        ArchiveDecisionResponse resp = service.recordDecision(1L, archivedReq());

        assertThat(resp.alreadyDecided()).isTrue();
        assertThat(resp.decision()).isEqualTo(ArchiveDecision.SKIPPED); // 返回既有决策
        verify(repo, never()).save(any());
        verify(archiver, never()).archiveImImagesToPrivate(anyLong(), anyList());
    }

    @Test
    void skippedDoesNotArchiveImages() {
        when(repo.findBySourceRef("ref-2")).thenReturn(Optional.empty());
        when(repo.save(any(HealthEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        var req = new ArchiveDecisionRequest(HealthSourceType.VET_CONSULT, "ref-2", 5L, ArchiveDecision.SKIPPED,
                null, null, null, null, List.of("im-1"));

        service.recordDecision(1L, req);

        verify(archiver, never()).archiveImImagesToPrivate(anyLong(), anyList());
    }

    @Test
    void hasDecisionDelegates() {
        when(repo.existsBySourceRef("ref-x")).thenReturn(true);
        assertThat(service.hasDecision("ref-x")).isTrue();
    }
}

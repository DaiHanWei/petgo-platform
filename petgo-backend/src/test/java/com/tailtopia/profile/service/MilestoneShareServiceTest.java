package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneLevel;
import com.tailtopia.profile.domain.MilestoneShare;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.MilestoneShareRequest;
import com.tailtopia.profile.dto.MilestoneShareResponse;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.MilestoneShareRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（P-35 分享链接）：新建生成 token / 幂等复用 / 未完成 422 / 无里程碑 404。owner 与 stable 字段全后端补。 */
class MilestoneShareServiceTest {

    private PetProfileRepository profiles;
    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private MilestoneShareRepository shares;
    private CardTokenGenerator tokenGenerator;
    private MilestoneShareService service;

    private static final long OWNER = 1L;
    private static final long PET_ID = 5L;
    private static final long MILESTONE_ID = 11L;
    private static final String CODE = "C-S5";

    @BeforeEach
    void setUp() {
        profiles = Mockito.mock(PetProfileRepository.class);
        milestones = Mockito.mock(PetMilestoneRepository.class);
        completions = Mockito.mock(MilestoneCompletionRepository.class);
        shares = Mockito.mock(MilestoneShareRepository.class);
        tokenGenerator = Mockito.mock(CardTokenGenerator.class);
        service = new MilestoneShareService(profiles, milestones, completions, shares, tokenGenerator);

        PetProfile pet = Mockito.mock(PetProfile.class);
        when(pet.getId()).thenReturn(PET_ID);
        when(pet.getName()).thenReturn("Momo");
        when(profiles.findByOwnerId(OWNER)).thenReturn(Optional.of(pet));

        PetMilestone m = Mockito.mock(PetMilestone.class);
        when(m.getId()).thenReturn(MILESTONE_ID);
        when(m.getLevel()).thenReturn(MilestoneLevel.S);
        when(milestones.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.of(m));

        MilestoneCompletion c = Mockito.mock(MilestoneCompletion.class);
        when(c.getCompletedAt()).thenReturn(Instant.parse("2026-06-01T00:00:00Z"));
        when(completions.findByPetMilestoneId(MILESTONE_ID)).thenReturn(Optional.of(c));
    }

    private MilestoneShareRequest req() {
        return new MilestoneShareRequest("Postingan pertama live! ✨", "Cerita Momo live.", "id", "LMS");
    }

    @Test
    void newShareGeneratesToken() {
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("TOK123");

        MilestoneShareResponse resp = service.createOrRefresh(OWNER, CODE, req());

        assertThat(resp.shareToken()).isEqualTo("TOK123");
        verify(shares).save(any(MilestoneShare.class));
    }

    @Test
    void existingShareReusedTokenNoNewGenerate() {
        MilestoneShare existing = Mockito.mock(MilestoneShare.class);
        when(existing.getShareToken()).thenReturn("OLDTOK");
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.of(existing));

        MilestoneShareResponse resp = service.createOrRefresh(OWNER, CODE, req());

        assertThat(resp.shareToken()).isEqualTo("OLDTOK");
        // V1.3.2 Story 5.2：refresh 多一个 collectionCodes 参数（老客户端不传 → null）。
        verify(existing).refresh(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull());
        verify(tokenGenerator, never()).generate();
        verify(shares, never()).save(any());
    }

    @Test
    void notCompletedRejected() {
        when(completions.findByPetMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrRefresh(OWNER, CODE, req()))
                .isInstanceOf(AppException.class);
        verify(shares, never()).save(any());
    }

    @Test
    void unknownMilestoneRejected() {
        when(milestones.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrRefresh(OWNER, CODE, req()))
                .isInstanceOf(AppException.class);
    }

    // ===== V1.3.2 Story 5.2：collection_codes =====

    private MilestoneShareRequest reqWithCodes(java.util.List<String> codes) {
        return new MilestoneShareRequest("Postingan pertama live! ✨", "Cerita Momo live.", "id", "SM", codes);
    }

    @Test
    void newShareWithCodesStoresCommaJoined() {
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("TOK123");
        var captor = org.mockito.ArgumentCaptor.forClass(MilestoneShare.class);

        service.createOrRefresh(OWNER, CODE, reqWithCodes(java.util.List.of("C-S5", "G-M1")));

        verify(shares).save(captor.capture());
        assertThat(captor.getValue().getCollectionCodes()).isEqualTo("C-S5,G-M1");
    }

    @Test
    void refreshWithCodesWritesThemAndOldClientClearsToNull() {
        MilestoneShare existing = Mockito.mock(MilestoneShare.class);
        when(existing.getShareToken()).thenReturn("OLDTOK");
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.of(existing));

        service.createOrRefresh(OWNER, CODE, reqWithCodes(java.util.List.of("C-S5")));
        verify(existing).refresh(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq("C-S5"));

        service.createOrRefresh(OWNER, CODE, req()); // 老客户端不传
        verify(existing).refresh(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void missingOrEmptyCodesStoreNull() {
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.empty());
        when(tokenGenerator.generate()).thenReturn("T1", "T2");
        var captor = org.mockito.ArgumentCaptor.forClass(MilestoneShare.class);

        service.createOrRefresh(OWNER, CODE, req());
        service.createOrRefresh(OWNER, CODE, reqWithCodes(java.util.List.of()));

        verify(shares, Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(sh -> assertThat(sh.getCollectionCodes()).isNull());
    }

    @Test
    void unknownCodeIsRejectedWith422() {
        when(shares.findByPetProfileIdAndCode(PET_ID, CODE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createOrRefresh(OWNER, CODE, reqWithCodes(java.util.List.of("C-S99"))))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(422));
        verify(shares, never()).save(any());
    }
}

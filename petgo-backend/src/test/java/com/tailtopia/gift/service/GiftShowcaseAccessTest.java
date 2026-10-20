package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** V1.4.0 gift Story 2.1 · AC3 · L0：礼物页可见性逐条对齐访客视图（封号主人放行，P-6）。 */
class GiftShowcaseAccessTest {

    private static final long PET = 30L;
    private static final long OWNER = 7L;
    private static final long VIEWER = 99L;

    private GiftPetDirectory pets;
    private UserHideRelationReader hides;
    private GiftShowcaseAccess access;

    @BeforeEach
    void setUp() {
        pets = mock(GiftPetDirectory.class);
        hides = mock(UserHideRelationReader.class);
        access = new GiftShowcaseAccess(pets, hides);
    }

    private static GiftPet pet(boolean ownerActive, boolean ownerDeleted) {
        return new GiftPet(PET, OWNER, "Mochi", null, "CAT", null, ownerActive, ownerDeleted);
    }

    private static AppException thrown(Runnable r) {
        try {
            r.run();
        } catch (AppException e) {
            return e;
        }
        throw new AssertionError("expected AppException");
    }

    @Test
    void missingPetIs404WithTheVisitorGoneDetail() {
        when(pets.pet(PET)).thenReturn(Optional.empty());
        AppException e = thrown(() -> access.requireVisible(VIEWER, PET));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getMessage()).contains("链接已失效");
    }

    @Test
    void deletedOwnerIs404() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(false, true)));
        assertThat(thrown(() -> access.requireVisible(VIEWER, PET)).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerBlockedOwnerIs403() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(true, false)));
        when(hides.isBlocked(VIEWER, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> access.requireVisible(VIEWER, PET))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void ownerBlockedViewerIsTheSame404AsMissing() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(true, false)));
        when(hides.isBlocked(OWNER, VIEWER)).thenReturn(true);
        AppException blocked = thrown(() -> access.requireVisible(VIEWER, PET));

        when(pets.pet(PET)).thenReturn(Optional.empty());
        AppException missing = thrown(() -> access.requireVisible(VIEWER, PET));

        assertThat(blocked.getStatus()).isEqualTo(HttpStatus.NOT_FOUND).isEqualTo(missing.getStatus());
        assertThat(blocked.getType()).isEqualTo(missing.getType());
        assertThat(blocked.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    void ownerViewingIsAllowedAsOwnerWithoutBlockLookups() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(true, false)));
        GiftShowcaseAccess.Visible v = access.requireVisible(OWNER, PET);
        assertThat(v.viewerIsOwner()).isTrue();
        assertThat(v.pet().petId()).isEqualTo(PET);
        verify(hides, never()).isBlocked(anyLong(), anyLong());
    }

    @Test
    void bannedButNotDeletedOwnerIsVisible() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(false, false)));
        GiftShowcaseAccess.Visible v = access.requireVisible(VIEWER, PET);
        assertThat(v.viewerIsOwner()).isFalse();
        assertThat(v.pet().ownerActive()).isFalse();
    }

    @Test
    void plainVisitorIsVisibleAsNonOwner() {
        when(pets.pet(PET)).thenReturn(Optional.of(pet(true, false)));
        assertThat(access.requireVisible(VIEWER, PET).viewerIsOwner()).isFalse();
    }
}

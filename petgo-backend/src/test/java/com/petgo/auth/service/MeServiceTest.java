package com.petgo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.petgo.auth.domain.PetStatus;
import com.petgo.auth.domain.User;
import com.petgo.auth.dto.UpdateMeRequest;
import com.petgo.auth.dto.UserProfileResponse;
import com.petgo.auth.repository.UserRepository;
import com.petgo.profile.repository.PetProfileRepository;
import com.petgo.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0 单元测试（无 DB）：昵称/状态更新 + onboarding 完成 + 校验（AC1/AC2/AC3）。
 */
@ExtendWith(MockitoExtension.class)
class MeServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PetProfileRepository petProfiles;

    @InjectMocks
    MeService meService;

    private User freshUser() {
        return User.newGoogleUser("sub-1", "a@b.com", "Alice", "http://pic");
    }

    @Test
    void updateNicknameWithinLimitPersists() {
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest("Buddy", null, null));

        assertThat(resp.nickname()).isEqualTo("Buddy");
        assertThat(u.getNickname()).isEqualTo("Buddy");
    }

    @Test
    void avatarUrlUpdatePersistsAppOwnedUrl() {
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(
                1L, new UpdateMeRequest(null, null, "https://oss/public/avatars/abc.jpg"));

        assertThat(resp.avatarUrl()).isEqualTo("https://oss/public/avatars/abc.jpg");
        assertThat(u.getAvatarUrl()).isEqualTo("https://oss/public/avatars/abc.jpg");
    }

    @Test
    void blankNicknameRejected() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        assertThatThrownBy(() -> meService.updateMe(1L, new UpdateMeRequest("   ", null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void petStatusSelectionCompletesOnboarding() {
        User u = freshUser();
        assertThat(u.isOnboardingCompleted()).isFalse();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, "A", null));

        assertThat(u.getPetStatus()).isEqualTo(PetStatus.A);
        assertThat(u.isOnboardingCompleted()).isTrue();
        assertThat(resp.onboardingCompleted()).isTrue();
    }

    @Test
    void invalidPetStatusRejected() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        assertThatThrownBy(() -> meService.updateMe(1L, new UpdateMeRequest(null, "Z", null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void getMeReturnsProfileWithHasPetProfileFalse() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        UserProfileResponse resp = meService.getMe(1L);
        assertThat(resp.hasPetProfile()).isFalse();
        assertThat(resp.displayName()).isEqualTo("Alice");
    }
}

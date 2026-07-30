package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.PetStatus;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.UpdateMeRequest;
import com.tailtopia.auth.dto.UserProfileResponse;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0 单元测试（无 DB）：昵称/状态更新 + onboarding 完成 + 校验（AC1/AC2/AC3）。
 */
@ExtendWith(MockitoExtension.class)
class MeServiceTest {

    @Mock
    UserRepository users;

    @Mock
    PetProfileRepository petProfiles;

    @Mock
    ApplicationEventPublisher events;

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

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, "HAS_PET", null));

        assertThat(u.getPetStatus()).isEqualTo(PetStatus.HAS_PET);
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
    void switchingAwayFromPetDoesNotDeletePetProfiles() {
        // AC5 / B5（FR-21）：A→B/C 状态切换绝不级联删除宠物档案（档案数据保留）。
        User u = freshUser();
        u.setPetStatus(PetStatus.HAS_PET);
        u.setOnboardingCompleted(true);
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, "PLANNING", null));

        assertThat(u.getPetStatus()).isEqualTo(PetStatus.PLANNING);
        assertThat(u.isOnboardingCompleted()).isTrue(); // onboarding 不回退
        assertThat(resp.petStatus()).isEqualTo(PetStatus.PLANNING);
        // 关键：状态切换路径对 pet_profiles 零删除调用（仅 existsByOwnerId 用于 hasPetProfile）
        verify(petProfiles, never()).delete(any());
        verify(petProfiles, never()).deleteById(any());
        verify(petProfiles, never()).deleteAll();
    }

    @Test
    void getMeReturnsProfileWithHasPetProfileFalse() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        UserProfileResponse resp = meService.getMe(1L);
        assertThat(resp.hasPetProfile()).isFalse();
        assertThat(resp.displayName()).isEqualTo("Alice");
    }
}

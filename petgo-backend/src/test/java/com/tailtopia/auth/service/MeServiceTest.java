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

    /** V1.3.4 follow Story 1.2：/me 四格统计（作者自视口径）。 */
    @Mock
    com.tailtopia.content.service.ContentService contentService;

    /** V1.4.0 ① Story 1.4：/me 成长值摘要。 */
    @Mock
    com.tailtopia.growth.service.GrowthPageQuery growth;

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

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest("Buddy", null, null, null, null));

        assertThat(resp.nickname()).isEqualTo("Buddy");
        assertThat(u.getNickname()).isEqualTo("Buddy");
    }

    @Test
    void avatarUrlUpdatePersistsAppOwnedUrl() {
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(
                1L, new UpdateMeRequest(null, null, "https://oss/public/avatars/abc.jpg", null, null));

        assertThat(resp.avatarUrl()).isEqualTo("https://oss/public/avatars/abc.jpg");
        assertThat(u.getAvatarUrl()).isEqualTo("https://oss/public/avatars/abc.jpg");
    }

    @Test
    void signatureUpdatePersists() { // bug 20260721-327：用户级一句话签名
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(
                1L, new UpdateMeRequest(null, null, null, "  Cinta anjing 🐶  ", null));

        assertThat(resp.signature()).isEqualTo("Cinta anjing 🐶"); // trim
        assertThat(u.getSignature()).isEqualTo("Cinta anjing 🐶");
    }

    @Test
    void signatureBlankClears() {
        User u = freshUser();
        u.setSignature("old");
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, null, null, "  ", null));

        assertThat(resp.signature()).isNull();
        assertThat(u.getSignature()).isNull();
    }

    @Test
    void blankNicknameRejected() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        assertThatThrownBy(() -> meService.updateMe(1L, new UpdateMeRequest("   ", null, null, null, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void petStatusSelectionCompletesOnboarding() {
        User u = freshUser();
        assertThat(u.isOnboardingCompleted()).isFalse();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, "HAS_PET", null, null, null));

        assertThat(u.getPetStatus()).isEqualTo(PetStatus.HAS_PET);
        assertThat(u.isOnboardingCompleted()).isTrue();
        assertThat(resp.onboardingCompleted()).isTrue();
    }

    @Test
    void invalidPetStatusRejected() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        assertThatThrownBy(() -> meService.updateMe(1L, new UpdateMeRequest(null, "Z", null, null, null)))
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

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest(null, "PLANNING", null, null, null));

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

    /** V1.3.4 follow Story 1.2 · AC5：GET /me 带 token、作者自视帖子数 / 获赞数与两关注计数。 */
    @Test
    void getMeCarriesOwnPostStatsAndFollowFields() {
        User u = freshUser();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "publicToken", "tok");
        org.springframework.test.util.ReflectionTestUtils.setField(u, "followingCount", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(u, "followerCount", 4);
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(contentService.countOwnPostsByAuthor(1L)).thenReturn(5L);
        when(contentService.sumLikesOnOwnPostsByAuthor(1L)).thenReturn(9L);

        UserProfileResponse resp = meService.getMe(1L);

        assertThat(resp.userToken()).isEqualTo("tok");
        assertThat(resp.postCount()).isEqualTo(5L);
        assertThat(resp.likeCount()).isEqualTo(9L);
        assertThat(resp.followingCount()).isEqualTo(3);
        assertThat(resp.followerCount()).isEqualTo(4);
        // ⚠️ 他人视角的 Public 统计不得被拿来当自视口径（D-5）。
        org.mockito.Mockito.verify(contentService, org.mockito.Mockito.never()).countPublicPostsByAuthor(1L);
    }

    /** AC5.4：PATCH 响应同样带统计 —— 缺了 App 用它覆盖本地状态时四格会被清空。 */
    @Test
    void updateMeResponseCarriesOwnPostStats() {
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(contentService.countOwnPostsByAuthor(1L)).thenReturn(2L);
        when(contentService.sumLikesOnOwnPostsByAuthor(1L)).thenReturn(6L);

        UserProfileResponse resp = meService.updateMe(1L, new UpdateMeRequest("Buddy", null, null, null, null));

        assertThat(resp.postCount()).isEqualTo(2L);
        assertThat(resp.likeCount()).isEqualTo(6L);
    }

    /** T-6：登录响应用的旧工厂不带帖子统计（null → NON_NULL 省略）。 */
    @Test
    void loginFactoryOmitsPostStats() {
        UserProfileResponse resp = UserProfileResponse.from(freshUser(), false);
        assertThat(resp.postCount()).isNull();
        assertThat(resp.likeCount()).isNull();
        assertThat(resp.growth()).isNull(); // V1.4.0 ① Story 1.4：登录不查成长值
    }

    private static final com.tailtopia.growth.web.dto.MeGrowthBrief BRIEF = new com.tailtopia.growth.web.dto.MeGrowthBrief(
            7, com.tailtopia.growth.domain.GrowthTier.BUDDY, com.tailtopia.growth.domain.FrameChoice.BASIC, 1420L, 1800L);

    /** V1.4.0 ① Story 1.4 · AC5：GET /me 带成长值摘要。 */
    @Test
    void getMeCarriesGrowthBrief() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        when(growth.brief(1L)).thenReturn(BRIEF);

        assertThat(meService.getMe(1L).growth()).isEqualTo(BRIEF);
    }

    /** V1.4.0 ① Story 1.4 · AC5.2：PATCH /me 响应同样带（否则 App 用 PATCH 响应覆盖后胶囊消失）。 */
    @Test
    void updateMeCarriesGrowthBrief() {
        User u = freshUser();
        when(users.findById(1L)).thenReturn(Optional.of(u));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(growth.brief(1L)).thenReturn(BRIEF);

        assertThat(meService.updateMe(1L, new UpdateMeRequest("Buddy", null, null, null, null)).growth())
                .isEqualTo(BRIEF);
    }

    /** 不参与者 growth = null（NON_NULL 省略）。 */
    @Test
    void nonParticipantHasNoGrowth() {
        when(users.findById(1L)).thenReturn(Optional.of(freshUser()));
        when(growth.brief(1L)).thenReturn(null);

        assertThat(meService.getMe(1L).growth()).isNull();
    }
}

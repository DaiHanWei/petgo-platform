package com.tailtopia.avatarmoderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.UpdateMeRequest;
import com.tailtopia.auth.service.MeService;
import com.tailtopia.avatarmoderation.domain.AvatarDecision;
import com.tailtopia.avatarmoderation.domain.AvatarDefaults;
import com.tailtopia.avatarmoderation.domain.AvatarPriority;
import com.tailtopia.avatarmoderation.domain.AvatarReview;
import com.tailtopia.avatarmoderation.domain.AvatarReviewStatus;
import com.tailtopia.avatarmoderation.domain.AvatarReviewVerdict;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import com.tailtopia.avatarmoderation.repository.AvatarReviewRepository;
import com.tailtopia.avatarmoderation.service.AvatarModerationService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.dto.PetProfileCreateRequest;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * L1：头像审核异步链端到端（内容审核 story 5，AC-B3/B4/B9/B10/B11/B12/B13）。
 *
 * <p>验证真实接线而非仅逻辑：换头像（先放行立即生效）→ {@code AvatarReviewRequestedEvent}
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} → {@code openReview}(REQUIRES_NEW) 落 QUEUED
 * → 图像评分路由落 MANUAL_PENDING → 运营 {@code decide(VIOLATION)} → 重置为平台默认头像常量 + {@code AVATAR_RESET}
 * 通知（AFTER_COMMIT/REQUIRES_NEW 真落库）。覆盖「notify AFTER_COMMIT 事务吞写」类风险的实际触达。
 *
 * <p>{@code @Async} executor 改为 {@link SyncTaskExecutor} 以确定性断言（生产为默认异步，见 {@code AsyncConfig}）；
 * 同步执行不改变 AFTER_COMMIT 语义与 REQUIRES_NEW 边界。stub 图像打分：URL 含 {@code stub-porn} → 高置信违规
 * （IMAGE_BLOCKED）；无标记 → PASS。
 */
class AvatarModerationIntegrationTest extends ApiIntegrationTest {

    private static final String VIOLATION_AVATAR = "https://cdn.tailtopia.test/u/stub-porn-avatar.jpg";
    private static final String NORMAL_AVATAR = "https://cdn.tailtopia.test/u/happy-cat.jpg";

    /** 让 {@code @Async} 在测试内同步执行（生产异步不受影响）。 */
    @TestConfiguration
    static class SyncAsyncConfig implements AsyncConfigurer {
        @Override
        public Executor getAsyncExecutor() {
            return new SyncTaskExecutor();
        }
    }

    @Autowired
    private MeService meService;
    @Autowired
    private ProfileService profileService;
    @Autowired
    private AvatarModerationService avatarModeration;
    @Autowired
    private AvatarReviewRepository reviews;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private NotificationRepository notifications;

    private List<AvatarReview> allReviews(AvatarSubjectType type, long subjectId) {
        return reviews.findBySubjectTypeAndSubjectIdAndStatusIn(type, subjectId, List.of(
                AvatarReviewStatus.QUEUED, AvatarReviewStatus.AUTO_PASSED,
                AvatarReviewStatus.MANUAL_PENDING, AvatarReviewStatus.RESOLVED));
    }

    private AvatarReview onlyReview(AvatarSubjectType type, long subjectId) {
        List<AvatarReview> all = allReviews(type, subjectId);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void userAvatarViolationResetsToDefaultAndNotifies_andResetItselfNotReReviewed() {
        User u = newUser();
        long uid = u.getId();

        // 换头像为违规图（stub-porn → IMAGE_BLOCKED）→ 先放行立即生效 + 异步送审入队标 HIGH（B3/B7）。
        meService.updateMe(uid, new UpdateMeRequest(null, null, VIOLATION_AVATAR, null));

        AvatarReview rec = onlyReview(AvatarSubjectType.USER_AVATAR, uid);
        assertThat(rec.getStatus()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(rec.getPriority()).isEqualTo(AvatarPriority.HIGH);
        assertThat(rec.getVerdict()).isEqualTo(AvatarReviewVerdict.PENDING_REVIEW);
        // 先放行（可见窗口期 D-CM2）：头像即送审值，审核未改它（无「审核中」中间态）。
        assertThat(users.findById(uid).orElseThrow().getAvatarUrl()).isEqualTo(VIOLATION_AVATAR);

        // 运营判违规 → 重置平台默认头像常量 + AVATAR_RESET 通知（B9）。
        avatarModeration.decide(rec.getId(), AvatarDecision.VIOLATION, 1L,
                new com.tailtopia.content.moderation.ModerationDecision("test-violation", null));

        User reset = users.findById(uid).orElseThrow();
        assertThat(reset.getAvatarUrl()).isEqualTo(AvatarDefaults.DEFAULT_USER_AVATAR_URL);
        assertThat(reviews.findById(rec.getId()).orElseThrow().getStatus())
                .isEqualTo(AvatarReviewStatus.RESOLVED);
        assertThat(reviews.findById(rec.getId()).orElseThrow().getVerdict())
                .isEqualTo(AvatarReviewVerdict.VIOLATION);

        List<Notification> notes = notifications.findByRecipientUserIdAndReadIsFalse(uid);
        assertThat(notes).anyMatch(n -> n.getType() == NotificationType.AVATAR_RESET);

        // B12：重置为默认头像的写操作本身不再触发送审（无第二条 review 生成，防自审循环）。
        assertThat(allReviews(AvatarSubjectType.USER_AVATAR, uid)).hasSize(1);
    }

    @Test
    void lowRiskAvatarAutoPassesWithoutQueueOrNotification() {
        User u = newUser();
        long uid = u.getId();

        meService.updateMe(uid, new UpdateMeRequest(null, null, NORMAL_AVATAR, null));

        AvatarReview rec = onlyReview(AvatarSubjectType.USER_AVATAR, uid);
        assertThat(rec.getStatus()).isEqualTo(AvatarReviewStatus.AUTO_PASSED); // B5
        assertThat(rec.getVerdict()).isEqualTo(AvatarReviewVerdict.PASS);
        // B13：PASS 不推送。
        assertThat(notifications.findByRecipientUserIdAndReadIsFalse(uid))
                .noneMatch(n -> n.getType() == NotificationType.AVATAR_RESET);
    }

    @Test
    void reuploadSupersedesOldPendingAsStaleAndReReviews() {
        User u = newUser();
        long uid = u.getId();

        meService.updateMe(uid, new UpdateMeRequest(null, null, VIOLATION_AVATAR, null));
        AvatarReview first = onlyReview(AvatarSubjectType.USER_AVATAR, uid);
        assertThat(first.getStatus()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);

        // 重传新头像 → 旧在途记录陈旧作废（RESOLVED/STALE_DISCARDED、移出队列），新记录重新送审（B10/B11）。
        meService.updateMe(uid, new UpdateMeRequest(null, null, NORMAL_AVATAR, null));

        AvatarReview reloadedFirst = reviews.findById(first.getId()).orElseThrow();
        assertThat(reloadedFirst.getStatus()).isEqualTo(AvatarReviewStatus.RESOLVED);
        assertThat(reloadedFirst.getVerdict()).isEqualTo(AvatarReviewVerdict.STALE_DISCARDED);
        // 新记录已生成（NORMAL 低风险 → AUTO_PASSED）。
        List<AvatarReview> all = allReviews(AvatarSubjectType.USER_AVATAR, uid);
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(r -> r.getId() != first.getId()
                && r.getStatus() == AvatarReviewStatus.AUTO_PASSED);
    }

    @Test
    void petAvatarCreateEnqueuesAndViolationResetsToDefault() {
        User owner = newUser();
        long ownerId = owner.getId();

        profileService.create(ownerId, new PetProfileCreateRequest(
                VIOLATION_AVATAR, "CAT", "Kitty", null, LocalDate.of(2020, 1, 1), null));
        PetProfile pet = petProfiles.findByOwnerId(ownerId).orElseThrow();
        long petId = pet.getId();

        AvatarReview rec = onlyReview(AvatarSubjectType.PET_AVATAR, petId); // B4
        assertThat(rec.getStatus()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(pet.getAvatarUrl()).isEqualTo(VIOLATION_AVATAR); // 先放行

        avatarModeration.decide(rec.getId(), AvatarDecision.VIOLATION, 1L,
                new com.tailtopia.content.moderation.ModerationDecision("test-violation", null));

        assertThat(petProfiles.findById(petId).orElseThrow().getAvatarUrl())
                .isEqualTo(AvatarDefaults.DEFAULT_PET_AVATAR_URL);
        // 通知发给 owner（recipient=owner user id）。
        assertThat(notifications.findByRecipientUserIdAndReadIsFalse(ownerId))
                .anyMatch(n -> n.getType() == NotificationType.AVATAR_RESET);
    }
}

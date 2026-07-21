package com.tailtopia.namemoderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.UpdateMeRequest;
import com.tailtopia.auth.service.MeService;
import com.tailtopia.namemoderation.domain.NameDecision;
import com.tailtopia.namemoderation.domain.NameModerationRecord;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NamePriority;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.repository.NameModerationRecordRepository;
import com.tailtopia.namemoderation.service.NameModerationService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * L1：名称审核异步链端到端（内容审核 story 4，AC-B6/B8）。
 *
 * <p>验证真实接线而非仅逻辑：改昵称（先放行立即生效）→ {@code NameSubmittedEvent}
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} → {@code openReview}(REQUIRES_NEW) 落 SCORING
 * → 评分路由落 MANUAL_PENDING → 运营 {@code decide(VIOLATION)} → 重置为默认编码名 + {@code NAME_RESET} 通知。
 * 覆盖过去「notify AFTER_COMMIT 事务吞写」类风险的实际触达。
 *
 * <p>{@code @Async} executor 在本测改为 {@link SyncTaskExecutor} 以确定性断言（生产为默认
 * {@code SimpleAsyncTaskExecutor}，见 {@code AsyncConfig}）；同步执行不改变 AFTER_COMMIT 语义与 REQUIRES_NEW 边界。
 */
class NameModerationIntegrationTest extends ApiIntegrationTest {

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
    private NameModerationService nameModeration;
    @Autowired
    private NameModerationRecordRepository records;
    @Autowired
    private NotificationRepository notifications;

    private NameModerationRecord latestNickname(long userId) {
        return records
                .findTopByTargetTypeAndTargetRefIdOrderByRevisionDesc(NameTargetType.NICKNAME, userId)
                .orElseThrow();
    }

    @Test
    void highRiskNicknameEnqueuesThenViolationResetsToDefaultAndNotifies() {
        User u = newUser();
        long uid = u.getId();

        // 改名为高风险（stub 评分 0.9 ≥0.8）→ 先放行立即生效 + 异步送审入队标 HIGH。
        meService.updateMe(uid, new UpdateMeRequest("stub-high", null, null, null));

        NameModerationRecord rec = latestNickname(uid);
        assertThat(rec.getStatus()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(rec.getPriority()).isEqualTo(NamePriority.HIGH);
        // 先放行：真实列即送审值，审核未改它（无「审核中」中间态）。
        assertThat(users.findById(uid).orElseThrow().getNickname()).isEqualTo("stub-high");

        // 运营判违规 → 重置默认编码名 + is_system_default_name=true + NAME_RESET 通知。
        nameModeration.decide(rec.getId(), NameDecision.VIOLATION, 1L,
                new com.tailtopia.content.moderation.ModerationDecision("test-violation", null));

        User reset = users.findById(uid).orElseThrow();
        assertThat(reset.getNickname()).startsWith("user_");
        assertThat(reset.isSystemDefaultName()).isTrue();
        assertThat(records.findById(rec.getId()).orElseThrow().getStatus())
                .isEqualTo(NameModerationStatus.RESOLVED_VIOLATION);

        List<Notification> notes = notifications.findByRecipientUserIdAndReadIsFalse(uid);
        assertThat(notes).anyMatch(n -> n.getType() == NotificationType.NAME_RESET);
    }

    @Test
    void reeditSupersedesOldPendingRecord() {
        User u = newUser();
        long uid = u.getId();

        meService.updateMe(uid, new UpdateMeRequest("stub-high", null, null, null));
        NameModerationRecord first = latestNickname(uid);
        assertThat(first.getStatus()).isEqualTo(NameModerationStatus.MANUAL_PENDING);

        // 再次改名 → 旧在途记录陈旧作废（SUPERSEDED、移出队列），新记录成为最新。
        meService.updateMe(uid, new UpdateMeRequest("stub-high2", null, null, null));

        assertThat(records.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(NameModerationStatus.SUPERSEDED);
        NameModerationRecord latest = latestNickname(uid);
        assertThat(latest.getId()).isNotEqualTo(first.getId());
        assertThat(latest.getRevision()).isGreaterThan(first.getRevision());
    }
}

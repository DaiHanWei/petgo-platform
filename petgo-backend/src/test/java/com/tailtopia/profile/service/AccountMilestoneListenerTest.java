package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tailtopia.content.event.ContentCollectedEvent;
import com.tailtopia.profile.domain.AccountMilestoneFlag;
import com.tailtopia.social.event.UserFollowedEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** L0（V1.4.2 Story 2.1 · P-3）：账号级里程碑触发监听。 */
class AccountMilestoneListenerTest {

    private final AccountMilestoneService service = mock(AccountMilestoneService.class);
    private final AccountMilestoneListener listener = new AccountMilestoneListener(service);

    @Test
    void followTriggersFirstFollowForFollower() {
        listener.onUserFollowed(new UserFollowedEvent(7L, 8L, Instant.now()));
        verify(service).onFlagTriggered(7L, AccountMilestoneFlag.FIRST_FOLLOW_USER);
    }

    /** PRD AC3.4：自收藏也触发（不跳过 collectorId == authorId）。 */
    @Test
    void selfCollectStillTriggers() {
        listener.onContentCollected(new ContentCollectedEvent(100L, 7L, 7L));
        verify(service).onFlagTriggered(7L, AccountMilestoneFlag.FIRST_COLLECT_CONTENT);
    }

    @Test
    void failureIsSwallowed() {
        doThrow(new IllegalStateException("boom")).when(service).onFlagTriggered(anyLong(),
                org.mockito.ArgumentMatchers.any());
        listener.onUserFollowed(new UserFollowedEvent(7L, 8L, Instant.now()));
        listener.onContentCollected(new ContentCollectedEvent(100L, 7L, 9L));
    }

    /** 🔴 写库监听纪律：监听 = @Async + AFTER_COMMIT；写库方法 = REQUIRES_NEW（另一个 bean，非自调用）。 */
    @Test
    void wiringFollowsWriteListenerDiscipline() throws NoSuchMethodException {
        for (String name : new String[] {"onUserFollowed", "onContentCollected"}) {
            Method m = java.util.Arrays.stream(AccountMilestoneListener.class.getMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(Async.class)).as(name).isNotNull();
            TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
            assertThat(tel).as(name).isNotNull();
            assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
        Method write = AccountMilestoneService.class.getMethod("onFlagTriggered", long.class,
                AccountMilestoneFlag.class);
        assertThat(write.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}

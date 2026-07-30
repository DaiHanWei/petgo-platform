package com.tailtopia.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.domain.DeletionStatus;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.auth.service.AuthAccountDeletionService;
import com.tailtopia.consult.service.ConsultAnonymizationService;
import com.tailtopia.notify.service.NotificationDeletionService;
import com.tailtopia.profile.service.ProfileDeletionService;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.media.MediaDeletionService;
import com.tailtopia.shared.media.PersonalMedia;
import com.tailtopia.triage.service.TriageDeletionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：注销级联编排——删除纯个人模块 + consult 匿名化 + OSS 私密/公开图删除 + IM 媒体删除 + 置 DONE。
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock AccountDeletionRepository deletions;
    @Mock ProfileDeletionService profileDeletion;
    @Mock TriageDeletionService triageDeletion;
    @Mock ConsultAnonymizationService consultAnonymization;
    @Mock NotificationDeletionService notificationDeletion;
    @Mock AuthAccountDeletionService authDeletion;
    @Mock MediaDeletionService mediaDeletion;
    @Mock TencentImClient imClient;
    @Mock ApplicationEventPublisher events;
    @Mock com.tailtopia.content.service.ContentService contentService;
    @Mock com.tailtopia.admin.moderation.service.ManualReviewService reviewService;
    @Mock com.tailtopia.moderation.violation.service.ViolationCountService violationCountService;

    private AccountDeletionService service() {
        return new AccountDeletionService(deletions, profileDeletion, triageDeletion,
                consultAnonymization, notificationDeletion, authDeletion, mediaDeletion, imClient, events,
                contentService, reviewService, violationCountService);
    }

    private AccountDeletion pending(long id, long userId) {
        AccountDeletion d = AccountDeletion.request(userId);
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    @Test
    void executeRunsFullCascadeAndMarksDone() {
        AccountDeletion d = pending(1L, 7L);
        when(deletions.findById(1L)).thenReturn(Optional.of(d));
        when(profileDeletion.deleteByUserId(7L)).thenReturn(PersonalMedia.ofPrivate(List.of("h1")));
        when(triageDeletion.deleteByUserId(7L)).thenReturn(PersonalMedia.ofPrivate(List.of("t1", "t2")));
        when(consultAnonymization.anonymizeByUserId(7L)).thenReturn(PersonalMedia.ofPrivate(List.of("c1")));
        when(authDeletion.deleteByUserId(7L))
                .thenReturn(new PersonalMedia(new java.util.ArrayList<>(), List.of("https://oss/avatar.jpg")));

        service().execute(1L);

        // 删除/匿名化各模块均被调用
        verify(profileDeletion).deleteByUserId(7L);
        verify(triageDeletion).deleteByUserId(7L);
        verify(consultAnonymization).anonymizeByUserId(7L);
        verify(notificationDeletion).deleteByUserId(7L);
        verify(authDeletion).deleteByUserId(7L);
        // OSS 私密图（h1+t1+t2+c1）+ 公开头像 + IM 媒体
        verify(mediaDeletion).deletePrivateKeys(anyList());
        verify(mediaDeletion).deletePublicByUrls(anyList());
        verify(imClient).deleteUserConversationMedia(anyString());
        // 状态机 DONE
        assertThat(d.getStatus()).isEqualTo(DeletionStatus.DONE);
    }

    @Test
    void requestDeletionPublishesEvent() {
        when(deletions.findByUserId(7L)).thenReturn(Optional.of(pending(1L, 7L)));
        service().requestDeletion(7L);
        // 受理 → 发事件触发异步级联（指定类型消歧 publishEvent 重载）。
        verify(events).publishEvent(any(com.tailtopia.account.event.AccountDeletionRequestedEvent.class));
    }

    @Test
    void alreadyDoneSkips() {
        AccountDeletion d = pending(1L, 7L);
        d.markProcessing();
        d.markDone();
        when(deletions.findById(1L)).thenReturn(Optional.of(d));

        service().execute(1L);

        verify(profileDeletion, org.mockito.Mockito.never()).deleteByUserId(anyLong());
    }
}

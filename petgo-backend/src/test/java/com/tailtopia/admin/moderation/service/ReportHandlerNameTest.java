package com.tailtopia.admin.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.moderation.repository.ManualReviewItemRepository;
import com.tailtopia.admin.places.repository.PlaceReportRepository;
import com.tailtopia.admin.places.service.AdminPlaceQueryService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.repository.CommentRepository;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.moderation.repository.ContentReportRepository;
import com.tailtopia.moderation.service.ReportService;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：内容举报处理人显示名（2026-09-25 code review #5）。 */
class ReportHandlerNameTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AdminAccountRepository admins = mock(AdminAccountRepository.class);
    private final ManualReviewWorkbenchService service = new ManualReviewWorkbenchService(
            mock(UnifiedTicketQueryService.class), mock(ManualReviewItemRepository.class),
            mock(com.tailtopia.namemoderation.repository.NameModerationRecordRepository.class),
            mock(com.tailtopia.avatarmoderation.repository.AvatarReviewRepository.class),
            mock(ReportService.class), mock(ContentReportRepository.class), mock(ContentService.class),
            mock(CommentRepository.class), users, mock(PetProfileRepository.class), admins,
            mock(PlaceReportRepository.class), mock(AdminPlaceQueryService.class));

    private static AdminAccount admin(long id, String email, String name) {
        AdminAccount a = AdminAccount.newSuperAdmin(email, name, "x");
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    @DisplayName("🔴 handled_by 是官方作者 users.id（role=ADMIN）→ 按同邮箱找后台账号，不是拿它当后台 id 查")
    void operatorUserIdResolvesToTheRightAdmin() {
        User op = User.newAdmin("boss@tailtopia.id", "Boss", "x");
        ReflectionTestUtils.setField(op, "id", 3L);
        when(users.findById(3L)).thenReturn(Optional.of(op));
        when(admins.findByLarkEmail("boss@tailtopia.id")).thenReturn(Optional.of(admin(1L, "boss@tailtopia.id", "超管 Boss")));
        // 3 号后台账号是另一个人 —— 旧实现会错显示成他
        when(admins.findById(3L)).thenReturn(Optional.of(admin(3L, "other@tailtopia.id", "别人")));

        assertThat(service.reportHandlerName(3L)).isEqualTo("超管 Boss");
    }

    @Test
    @DisplayName("没有官方作者身份的后台账号（存的是后台 id）→ 按后台账号 id 查")
    void adminAccountIdFallsBack() {
        when(users.findById(7L)).thenReturn(Optional.empty());
        when(admins.findById(7L)).thenReturn(Optional.of(admin(7L, "staff@tailtopia.id", "运营小王")));

        assertThat(service.reportHandlerName(7L)).isEqualTo("运营小王");
    }

    @Test
    @DisplayName("id 碰上的是普通用户（非 ADMIN）→ 不按用户解，回退后台账号 / #id")
    void plainUserIsNotMistakenForOperator() {
        User normal = User.newGoogleUser("sub", "u@x.com", "U", null);
        ReflectionTestUtils.setField(normal, "id", 9L);
        when(users.findById(9L)).thenReturn(Optional.of(normal));
        when(admins.findById(9L)).thenReturn(Optional.empty());

        assertThat(service.reportHandlerName(9L)).isEqualTo("#9");
    }
}

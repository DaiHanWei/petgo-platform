package com.tailtopia.admin.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.consult.service.ConsultHistoryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.profile.service.ProfileService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：用户只读聚合（Story 3.1，mock owning service）——搜索命中/未命中、详情装配、问诊仅元数据。 */
class AdminUserServiceTest {

    private AccountQueryService accountQuery;
    private ProfileService profileService;
    private ContentService contentService;
    private ConsultHistoryService consultHistory;
    private com.tailtopia.auth.service.AuthService authService;
    private com.tailtopia.consult.service.ConsultInterruptService consultInterrupt;
    private com.tailtopia.admin.audit.service.AdminAuditService auditService;
    private com.tailtopia.account.service.AccountDeletionService accountDeletion;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        accountQuery = mock(AccountQueryService.class);
        profileService = mock(ProfileService.class);
        contentService = mock(ContentService.class);
        consultHistory = mock(ConsultHistoryService.class);
        authService = mock(com.tailtopia.auth.service.AuthService.class);
        consultInterrupt = mock(com.tailtopia.consult.service.ConsultInterruptService.class);
        auditService = mock(com.tailtopia.admin.audit.service.AdminAuditService.class);
        accountDeletion = mock(com.tailtopia.account.service.AccountDeletionService.class);
        service = new AdminUserService(accountQuery, profileService, contentService, consultHistory,
                authService, consultInterrupt, auditService, accountDeletion);
    }

    private User user() {
        User u = mock(User.class);
        when(u.getId()).thenReturn(42L);
        when(u.getDisplayName()).thenReturn("小明");
        when(u.getNickname()).thenReturn("明明");
        when(u.getEmail()).thenReturn("ming@x.com");
        when(u.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return u;
    }

    @Test
    void searchByIdHits() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        var rows = service.search("42");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).email()).isEqualTo("ming@x.com");
        // 数字查询不走邮箱分支。
        verify(accountQuery, never()).findUserByEmail(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void searchByEmailHits() {
        User u = user();
        when(accountQuery.findUserByEmail("ming@x.com")).thenReturn(Optional.of(u));
        var rows = service.search("ming@x.com");
        assertThat(rows).hasSize(1);
        verify(accountQuery, never()).findUserById(anyLong());
    }

    /** bug 20260701-164：列表页分页列出全部普通用户，逐条映射为行。 */
    @Test
    void listPagesAllUsers() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 50);
        User u = user();
        when(accountQuery.listUsers(pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(u), pageable, 1));
        var page = service.list(pageable);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).email()).isEqualTo("ming@x.com");
    }

    @Test
    void searchMissReturnsEmpty() {
        when(accountQuery.findUserByEmail("none@x.com")).thenReturn(Optional.empty());
        assertThat(service.search("none@x.com")).isEmpty();
        assertThat(service.search("  ")).isEmpty();
    }

    @Test
    void detailAggregatesFiveBlocksViaOwningServices() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        when(profileService.findByOwnerId(42L)).thenReturn(Optional.empty());
        when(contentService.listByAuthorForAdmin(42L)).thenReturn(List.of(
                new ContentService.PostSummary(7L, com.tailtopia.content.domain.ContentType.DAILY, "hi", false, 42L)));
        when(consultHistory.adminSessionMetadata(42L)).thenReturn(List.of(
                new ConsultHistoryService.SessionMeta(9L, 3L, "CLOSED", Instant.now(), Instant.now(), 5)));

        AdminUserDetailView v = service.detail(42L);

        assertThat(v.id()).isEqualTo(42L);
        assertThat(v.email()).isEqualTo("ming@x.com");
        assertThat(v.posts()).hasSize(1);
        assertThat(v.sessions()).hasSize(1);
        assertThat(v.sessions().get(0).stars()).isEqualTo(5);
        // 经各 owning service 读（不直读 repo）。
        verify(contentService).listByAuthorForAdmin(42L);
        verify(consultHistory).adminSessionMetadata(42L);
    }

    @Test
    void detailUnknownUserThrows() {
        when(accountQuery.findUserById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(99L)).isInstanceOf(AppException.class);
    }

    // ===== Story 3.2：停用/激活 =====

    @Test
    void deactivateRequiresReasonOrchestratesAndAudits() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));

        // 原因空 → 拒绝，且不触发任何副作用。
        assertThatThrownBy(() -> service.deactivate(42L, "  ", 3L)).isInstanceOf(AppException.class);
        verify(authService, never()).deactivateUser(anyLong());

        service.deactivate(42L, "违规发布", 3L);
        verify(authService).deactivateUser(42L);
        verify(consultInterrupt).interruptByUser(42L);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.audit.service.AuditActions.USER_DEACTIVATED),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("42"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reactivateOrchestratesAndAudits() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        service.reactivate(42L, 3L);
        verify(authService).reactivateUser(42L);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.audit.service.AuditActions.USER_REACTIVATED),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("42"),
                org.mockito.ArgumentMatchers.any());
    }

    // ===== Story 3.3：删除（D1/D2）=====

    @Test
    void deleteRequiresTypeAndNote() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> service.deleteUser(42L, null, "x", 3L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.deleteUser(42L,
                com.tailtopia.admin.usermgmt.domain.DeletionType.USER_REQUEST, "  ", 3L))
                .isInstanceOf(AppException.class);
        verify(accountDeletion, never()).requestDeletion(anyLong());
    }

    @Test
    void d1DeleteAuditsAndCascadesWithoutTakedown() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        service.deleteUser(42L, com.tailtopia.admin.usermgmt.domain.DeletionType.USER_REQUEST, "用户申请", 3L);

        verify(auditService).record(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(com.tailtopia.admin.audit.service.AuditActions.USER_DELETED),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("42"),
                org.mockito.ArgumentMatchers.any());
        verify(contentService, never()).takedownAllByAuthor(anyLong()); // D1 不下架
        verify(accountDeletion).requestDeletion(42L);
    }

    @Test
    void d2DeleteTakesDownContentThenCascades() {
        User u = user();
        when(accountQuery.findUserById(42L)).thenReturn(Optional.of(u));
        service.deleteUser(42L, com.tailtopia.admin.usermgmt.domain.DeletionType.VIOLATION, "违规内容", 3L);

        verify(contentService).takedownAllByAuthor(42L); // D2 先下架
        verify(accountDeletion).requestDeletion(42L);
    }
}

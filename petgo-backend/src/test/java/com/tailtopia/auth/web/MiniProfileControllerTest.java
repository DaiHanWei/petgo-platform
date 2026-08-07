package com.tailtopia.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.MiniProfileResponse;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：迷你主页投影 + 发布数 + 已注销过滤（AC1/AC2 逻辑面）。 */
class MiniProfileControllerTest {

    private AccountQueryService accounts;
    private ContentService content;
    private MiniProfileController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        content = mock(ContentService.class);
        controller = new MiniProfileController(accounts, content);
    }

    @Test
    void activeUserReturnsNicknameAvatarPostCount() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", "https://cdn/a.jpg", false)));
        when(accounts.activeSignatureOf(7L)).thenReturn(java.util.Optional.of("爱猫的人运气都不会太差"));
        when(content.countPublishedByAuthor(7L)).thenReturn(2L);

        MiniProfileResponse r = controller.miniProfile(7L);
        assertThat(r.isDeactivated()).isFalse();
        assertThat(r.nickname()).isEqualTo("Alice");
        assertThat(r.avatarUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(r.signature()).isEqualTo("爱猫的人运气都不会太差");
        assertThat(r.postCount()).isEqualTo(2L);
    }

    /** 没设签名 → null（前端据此回落「主页筹备中」占位）。 */
    @Test
    void userWithoutSignatureReturnsNull() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", null, false)));
        when(accounts.activeSignatureOf(7L)).thenReturn(java.util.Optional.empty());
        when(content.countPublishedByAuthor(7L)).thenReturn(0L);

        assertThat(controller.miniProfile(7L).signature()).isNull();
    }

    @Test
    void deactivatedUserReturnsFlagAndNoIdentityNoPostCountQuery() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        MiniProfileResponse r = controller.miniProfile(8L);
        assertThat(r.isDeactivated()).isTrue();
        assertThat(r.nickname()).isNull();
        assertThat(r.avatarUrl()).isNull();
        // 🔒 注销不外泄签名（NFR-8 匿名化）——签名是用户自填文本，同属身份信息。
        assertThat(r.signature()).isNull();
        // 注销不查发布数、也不查签名（不暴露任何信息、也不白打一次库）。
        verify(content, never()).countPublishedByAuthor(eq(8L));
        verify(accounts, never()).activeSignatureOf(eq(8L));
    }
}

package com.petgo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.dto.MiniProfileResponse;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.service.ContentService;
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
        when(content.countPublishedByAuthor(7L)).thenReturn(2L);

        MiniProfileResponse r = controller.miniProfile(7L);
        assertThat(r.isDeactivated()).isFalse();
        assertThat(r.nickname()).isEqualTo("Alice");
        assertThat(r.avatarUrl()).isEqualTo("https://cdn/a.jpg");
        assertThat(r.postCount()).isEqualTo(2L);
    }

    @Test
    void deactivatedUserReturnsFlagAndNoIdentityNoPostCountQuery() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        MiniProfileResponse r = controller.miniProfile(8L);
        assertThat(r.isDeactivated()).isTrue();
        assertThat(r.nickname()).isNull();
        assertThat(r.avatarUrl()).isNull();
        // 注销不查发布数（不暴露任何信息）。
        verify(content, never()).countPublishedByAuthor(eq(8L));
    }
}

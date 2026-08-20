package com.tailtopia.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.dto.MiniProfileResponse;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：迷你主页投影 + 发布数 + 已注销过滤（AC1/AC2 逻辑面）；Story 1.1 追加主动拉黑拦截（AC6/AC7）。 */
class MiniProfileControllerTest {

    private AccountQueryService accounts;
    private ContentService content;
    private UserHideRelationReader hideRelations;
    private MiniProfileController controller;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountQueryService.class);
        content = mock(ContentService.class);
        hideRelations = mock(UserHideRelationReader.class);
        controller = new MiniProfileController(accounts, content, hideRelations);
    }

    /** 游客（无 JWT）—— 既有三个用例的原语义，Story 1.1 后行为必须一字不变。 */
    private static Jwt guest() {
        return null;
    }

    /**
     * 已登录访问者的 JWT。
     *
     * <p>⚠️ {@code role=USER} 这个 claim 不能省：{@code viewerId()} 拿不到它就返回 null，
     * 于后拉黑守卫、「已举报」派生全部整段跳过 —— 测试会假绿（守卫没跑却以为跑了）。
     * 线上由 {@code JwtService.issueAccessToken(sub, role)} 签发，一定带 role。
     */
    private static Jwt viewer(long userId) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("role", "USER")
                .build();
    }

    @Test
    void activeUserReturnsNicknameAvatarPostCount() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", "https://cdn/a.jpg", false)));
        when(accounts.activeSignatureOf(7L)).thenReturn(java.util.Optional.of("爱猫的人运气都不会太差"));
        when(content.countPublishedByAuthor(7L)).thenReturn(2L);

        MiniProfileResponse r = controller.miniProfile(guest(), 7L);
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

        assertThat(controller.miniProfile(guest(), 7L).signature()).isNull();
    }

    @Test
    void deactivatedUserReturnsFlagAndNoIdentityNoPostCountQuery() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(8L, AuthorView.anonymized(8L)));

        MiniProfileResponse r = controller.miniProfile(guest(), 8L);
        assertThat(r.isDeactivated()).isTrue();
        assertThat(r.nickname()).isNull();
        assertThat(r.avatarUrl()).isNull();
        // 🔒 注销不外泄签名（NFR-8 匿名化）——签名是用户自填文本，同属身份信息。
        assertThat(r.signature()).isNull();
        // 注销不查发布数、也不查签名（不暴露任何信息、也不白打一次库）。
        verify(content, never()).countPublishedByAuthor(eq(8L));
        verify(accounts, never()).activeSignatureOf(eq(8L));
    }

    /** 游客一律不查隐藏关系（Story 1.1 AC6：游客行为完全不变）。 */
    @Test
    void guestNeverConsultsHideRelations() {
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(7L, new AuthorView(7L, "Alice", null, false)));
        when(accounts.activeSignatureOf(7L)).thenReturn(java.util.Optional.empty());
        when(content.countPublishedByAuthor(7L)).thenReturn(0L);

        controller.miniProfile(guest(), 7L);

        verify(hideRelations, never()).isBlocked(anyLong(), anyLong());
    }

    /** AC6：已主动拉黑 → 403 blocked-user，且**一个展示字段都不查**（AD-11）。 */
    @Test
    void blockedTargetThrows403AndTouchesNoProfileData() {
        when(hideRelations.isBlocked(5L, 9L)).thenReturn(true);

        assertThatThrownBy(() -> controller.miniProfile(viewer(5L), 9L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ex = (AppException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getType()).isEqualTo(ErrorTypes.BLOCKED_USER);
                });

        // 拦在取数之前：不查投影、不查签名、不查发布数。
        verify(accounts, never()).findAuthorViews(anyList());
        verify(accounts, never()).activeSignatureOf(anyLong());
        verify(content, never()).countPublishedByAuthor(anyLong());
    }

    /**
     * ⚠️ AC7（高风险点 R1）：**只举报过、未主动拉黑 → 照常返回卡片数据**。
     *
     * <p>把拦截条件写成「存在隐藏关系即拦」会一并把举报隐藏拦掉——「已举报」状态无处显示、
     * 重复举报无入口，FR-58 闭环当场作废。本用例就是钉死这一点的。
     */
    @Test
    void reportOnlyTargetIsNotBlockedAndReturnsCardData() {
        // 只举报过：isBlocked=false（isHidden 会是 true，但主页校验不该看它）
        when(hideRelations.isBlocked(5L, 9L)).thenReturn(false);
        when(accounts.findAuthorViews(anyList()))
                .thenReturn(Map.of(9L, new AuthorView(9L, "Rina", "https://cdn/r.jpg", false)));
        when(accounts.activeSignatureOf(9L)).thenReturn(java.util.Optional.empty());
        when(content.countPublishedByAuthor(9L)).thenReturn(3L);

        MiniProfileResponse r = controller.miniProfile(viewer(5L), 9L);

        assertThat(r.isDeactivated()).isFalse();
        assertThat(r.nickname()).isEqualTo("Rina");
        assertThat(r.postCount()).isEqualTo(3L);
    }
}

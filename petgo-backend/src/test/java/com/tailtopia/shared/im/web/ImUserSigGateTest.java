package com.tailtopia.shared.im.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.consult.service.ConsultSessionService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.im.UserSig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0 单元测试（无 Spring / 无 DB）：{@link ImUserSigController} 的用户态 MAU 闸门矩阵——
 * 直接 new 控制器 + mock 依赖，覆盖 I/O Matrix 的 403/恒签/401。
 */
class ImUserSigGateTest {

    private final TencentImClient imClient = mock(TencentImClient.class);
    private final ConsultSessionService consultSessions = mock(ConsultSessionService.class);
    private final ImUserSigController controller = new ImUserSigController(imClient, consultSessions);

    private static Jwt jwt(String sub, String role) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("role", role).build();
    }

    @Test
    void userWithActiveSessionGetsSignedUserSig() {
        when(consultSessions.hasImLoginEligibleSession(7L)).thenReturn(true);
        when(imClient.signUserSig("u_7")).thenReturn(new UserSig("u_7", "real-sig", "20043419", 86400));

        UserSig sig = controller.userSig(jwt("7", "USER"));

        assertThat(sig.imUserId()).isEqualTo("u_7");
        assertThat(sig.userSig()).isEqualTo("real-sig");
        verify(imClient).signUserSig("u_7");
    }

    @Test
    void userWithoutActiveSessionIsForbidden() {
        when(consultSessions.hasImLoginEligibleSession(7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.userSig(jwt("7", "USER")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        // 不签发，锁死无关用户吃 MAU。
        verify(imClient, never()).signUserSig(any());
    }

    @Test
    void vetIsAlwaysSignedWithoutSessionCheck() {
        when(imClient.signUserSig("v_3")).thenReturn(new UserSig("v_3", "vet-sig", "20043419", 86400));

        UserSig sig = controller.userSig(jwt("3", "VET"));

        assertThat(sig.imUserId()).isEqualTo("v_3");
        // 兽医恒签：绝不查会话闸门。
        verify(consultSessions, never()).hasImLoginEligibleSession(org.mockito.ArgumentMatchers.anyLong());
        verify(imClient).signUserSig("v_3");
    }

    @Test
    void missingJwtIsUnauthorized() {
        assertThatThrownBy(() -> controller.userSig(null))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void nonNumericSubjectIsUnauthorized() {
        assertThatThrownBy(() -> controller.userSig(jwt("not-a-number", "USER")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}

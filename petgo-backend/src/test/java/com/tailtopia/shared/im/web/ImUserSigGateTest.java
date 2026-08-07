package com.tailtopia.shared.im.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.im.TencentImClient;
import com.tailtopia.shared.im.UserSig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0 单元测试（无 Spring / 无 DB）：{@link ImUserSigController} 签发矩阵。
 *
 * <p>🔄 2026-08-07 推送接入决策：原「USER 须有进行中会话」MAU 硬门控已放宽为
 * <b>登录用户一律签发</b>（否则从未问诊的用户无法注册 TIMPush 离线推送，FR-22B/40~42 失效）。
 * 本测试同步改写：USER 无会话也签；401 矩阵不变。
 */
class ImUserSigGateTest {

    private final TencentImClient imClient = mock(TencentImClient.class);
    private final ImUserSigController controller = new ImUserSigController(imClient);

    private static Jwt jwt(String sub, String role) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("role", role).build();
    }

    @Test
    void userIsSignedWithoutAnySessionPrecondition() {
        // 放宽后的关键行为：无任何会话前置，登录用户即签（推送注册依赖 IM 登录）。
        when(imClient.signUserSig("u_7")).thenReturn(new UserSig("u_7", "real-sig", "20043419", 86400));

        UserSig sig = controller.userSig(jwt("7", "USER"));

        assertThat(sig.imUserId()).isEqualTo("u_7");
        assertThat(sig.userSig()).isEqualTo("real-sig");
        verify(imClient).signUserSig("u_7");
    }

    @Test
    void vetIsAlwaysSigned() {
        when(imClient.signUserSig("v_3")).thenReturn(new UserSig("v_3", "vet-sig", "20043419", 86400));

        UserSig sig = controller.userSig(jwt("3", "VET"));

        assertThat(sig.imUserId()).isEqualTo("v_3");
        verify(imClient).signUserSig("v_3");
    }

    @Test
    void unknownRoleIsForbidden() {
        // 放宽会话闸门后的 role 白名单：非 USER/VET（claim 缺失/未知角色）绝不默认按 USER 签发。
        assertThatThrownBy(() -> controller.userSig(jwt("7", "SOMETHING_ELSE")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        Jwt noRole = Jwt.withTokenValue("t").header("alg", "HS256").subject("7").build();
        assertThatThrownBy(() -> controller.userSig(noRole))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
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

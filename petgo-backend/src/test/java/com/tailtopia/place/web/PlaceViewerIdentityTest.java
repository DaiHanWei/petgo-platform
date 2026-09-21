package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0（反射调私有 helper，无 Spring/DB）：**游客可读的场所端点不得把兽医 token 当成用户**
 * （V1.3.0 batch-b1 Story 1.7 · 安全攸关 · code-review 2026-09-15）。
 *
 * <h2>为什么这条必须钉住</h2>
 * 兽医 token 的 {@code sub} 是 **vetId**，与 {@code users.id} 是两个会大量碰撞的命名空间。
 * 只看 sub 的话，一个兽医打开场所详情就会被当成「users.id 恰好等于该 vetId 的那个用户」——
 * 于是**那个人尚未过审 / 已被下架的评论原文会下发给他**，还标着 {@code mine=true}。
 * 界面上看不出任何异常，两侧日志里也什么都没有。
 *
 * <p>写端点靠 {@code SecurityConfig} 的 {@code hasRole("USER")} 挡住了同一个坑；
 * 读端点对游客放行，挡不住 —— 所以门只能写在 controller 的 helper 里，而这条测试盯着它。
 */
class PlaceViewerIdentityTest {

    private static Jwt jwt(String sub, String role) {
        return Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject(sub)
                .claim("role", role)
                .build();
    }

    private static Object optionalUserId(Class<?> controller, Jwt token) {
        try {
            Method m = controller.getDeclaredMethod("optionalUserId", Jwt.class);
            m.setAccessible(true);
            return m.invoke(null, token);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    controller.getSimpleName() + ".optionalUserId 改名了，改这里", e);
        }
    }

    private static final List<Class<?>> GUEST_READABLE_CONTROLLERS =
            List.of(PlaceController.class, PlaceCommentController.class);

    @Test
    void userTokenResolvesToItsUserId() {
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, jwt("4021", "USER"))).isEqualTo(4021L);
        }
    }

    @Test
    void vetTokenIsTreatedAsGuestNotAsTheUserWithThatId() {
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, jwt("4021", "VET")))
                    .as("🔴 %s 把兽医 token 的 sub 当成了 users.id —— "
                            + "那个用户未过审/被下架的评论会泄漏给兽医", c.getSimpleName())
                    .isNull();
        }
    }

    @Test
    void adminTokenIsAlsoTreatedAsGuest() {
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, jwt("1", "ADMIN"))).isNull();
        }
    }

    @Test
    void noTokenIsAGuest() {
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, null)).isNull();
        }
    }

    /** 形状不对的 sub（非数字）→ 按游客读，不是 500。 */
    @Test
    void malformedSubjectFallsBackToGuest() {
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, jwt("not-a-number", "USER"))).isNull();
        }
    }

    /** 没有 role claim（旧 token / 伪造）→ 同样按游客读，fail-closed。 */
    @Test
    void missingRoleClaimFallsBackToGuest() {
        Jwt noRole = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .subject("4021")
                .claim("other", Map.of())
                .build();
        for (Class<?> c : GUEST_READABLE_CONTROLLERS) {
            assertThat(optionalUserId(c, noRole)).isNull();
        }
    }
}

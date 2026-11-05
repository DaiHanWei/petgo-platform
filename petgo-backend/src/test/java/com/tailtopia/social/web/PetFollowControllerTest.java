package com.tailtopia.social.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.dto.FollowedPetPage;
import com.tailtopia.social.dto.PetFollowResultResponse;
import com.tailtopia.social.service.PetFollowService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** L0：追更端点（V1.4.2 social-topic · Story 1.1 · AC3 / AC4 / AC8）—— 无 JWT 401、路径参数非数字 400、viewer 只认 USER。 */
class PetFollowControllerTest {

    private final PetFollowService service = mock(PetFollowService.class);
    private final PetFollowController controller = new PetFollowController(service);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").claim("role", "USER").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noJwtIs401() {
        assertThatThrownBy(() -> controller.follow(null, 1L)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getStatus().value()).isEqualTo(401));
        assertThatThrownBy(() -> controller.unfollow(null, 1L)).isInstanceOf(AppException.class);
        verify(service, never()).follow(anyLong(), anyLong());
    }

    @Test
    void nonNumericPetIdIs400() throws Exception {
        mvc.perform(post("/api/v1/me/pet-follows/abc")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/me/pet-follows/abc")).andExpect(status().isBadRequest());
        verify(service, never()).follow(anyLong(), anyLong());
    }

    @Test
    void postAndDeleteUseJwtSubjectAndCamelCaseBody() throws Exception {
        when(service.follow(7L, 42L)).thenReturn(new PetFollowResultResponse(true, 3L));
        when(service.unfollow(7L, 42L)).thenReturn(new PetFollowResultResponse(false, 2L));

        mvc.perform(post("/api/v1/me/pet-follows/42")).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(true))
                .andExpect(jsonPath("$.followerCount").value(3));
        mvc.perform(delete("/api/v1/me/pet-follows/42")).andExpect(status().isOk())
                .andExpect(jsonPath("$.iFollow").value(false))
                .andExpect(jsonPath("$.followerCount").value(2));
    }

    /** 列表 viewer：游客 / 非 USER 角色 → null（兽医 sub 与 users.id 撞号）。 */
    @Test
    void listViewerOnlyTrustsUserRole() {
        when(service.followingOf("tok", null, null)).thenReturn(new FollowedPetPage(List.of(), null, false, 0L));
        Jwt vet = Jwt.withTokenValue("t").header("alg", "none").subject("7").claim("role", "VET").build();

        controller.followingPets(null, "tok", null);
        controller.followingPets(vet, "tok", null);

        verify(service, org.mockito.Mockito.times(2)).followingOf("tok", null, null);
        assertThat(PetFollowController.viewerId(
                Jwt.withTokenValue("t").header("alg", "none").subject("7").claim("role", "USER").build()))
                .isEqualTo(7L);
    }
}

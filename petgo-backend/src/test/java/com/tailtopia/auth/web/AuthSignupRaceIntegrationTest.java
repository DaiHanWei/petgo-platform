package com.tailtopia.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * L1：并发首登撞唯一约束时不再 500。
 *
 * <p>用 spy 让「查账号」对一次调用返回空，重现败者「没查到、去插入」的时序，
 * 插入本身走真实数据库，撞的是真实唯一约束。
 */
class AuthSignupRaceIntegrationTest extends ApiIntegrationTest {

    @MockitoSpyBean
    private UserRepository users;

    private JsonNode login(String path, String field) throws Exception {
        String body = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(field, "any-dev-token"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    @DisplayName("Google 并发首登的败者取到胜者建好的账号，返回 200")
    void googleRaceLoserGetsExistingAccount() throws Exception {
        long winnerId = login("/api/v1/auth/google", "idToken").at("/profile/id").asLong();
        Optional<User> winner = users.findById(winnerId);
        doReturn(Optional.empty()).doReturn(winner).when(users).findByGoogleSub(anyString());

        JsonNode loser = login("/api/v1/auth/google", "idToken");

        assertThat(loser.at("/profile/id").asLong()).isEqualTo(winnerId);
        assertThat(loser.get("isNewUser").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Apple 并发首登的败者取到胜者建好的账号，返回 200")
    void appleRaceLoserGetsExistingAccount() throws Exception {
        long winnerId = login("/api/v1/auth/apple", "identityToken").at("/profile/id").asLong();
        Optional<User> winner = users.findById(winnerId);
        doReturn(Optional.empty()).doReturn(winner).when(users).findByAppleSub(anyString());

        JsonNode loser = login("/api/v1/auth/apple", "identityToken");

        assertThat(loser.at("/profile/id").asLong()).isEqualTo(winnerId);
        assertThat(loser.get("isNewUser").asBoolean()).isFalse();
    }
}

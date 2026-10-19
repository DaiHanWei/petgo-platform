package com.tailtopia.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.account.domain.AccountDeletion;
import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.account.service.AccountDeletionService;
import com.tailtopia.auth.domain.User;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.preference.PushCategory;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.support.VetTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：V1.4.0 ④ Story 1.1 —— {@code GET / PATCH /api/v1/me/push-settings} + {@code user_push_mutes} + 注销清理。
 */
class PushSettingsEndpointIntegrationTest extends ApiIntegrationTest {

    private static final String URL = "/api/v1/me/push-settings";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PushPreferenceService prefs;
    @Autowired
    private VetTestSupport vets;
    @Autowired
    private AccountDeletionRepository deletions;
    @Autowired
    private AccountDeletionService accountDeletion;

    private long rows(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_push_mutes WHERE user_id = ?", Long.class, userId);
    }

    private void patchBody(long userId, String body, int expectStatus) throws Exception {
        mvc.perform(patch(URL).header("Authorization", userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectStatus));
    }

    @Test
    @DisplayName("新用户 GET → 9 项全开，只有 ORDER_ACCOUNT 不可关；库里 0 行")
    void freshUserAllEnabled() throws Exception {
        User u = newUser();
        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(9))
                .andExpect(jsonPath("$.categories[0].code").value("LIKE"))
                .andExpect(jsonPath("$.categories[8].code").value("ORDER_ACCOUNT"))
                .andExpect(jsonPath("$.categories[8].lockable").value(false))
                .andExpect(jsonPath("$.categories[0].lockable").value(true))
                .andExpect(jsonPath("$.categories[?(@.enabled == false)]").isEmpty());
        assertThat(rows(u.getId())).isZero();
    }

    @Test
    @DisplayName("PRD AC4：手机 A 关两项，手机 B（另一个 token）读到同样设置；部分更新 + 幂等")
    void settingsFollowAccount() throws Exception {
        User u = newUser();
        patchBody(u.getId(), "{\"categories\":{\"LIKE\":false,\"FOLLOWER\":false}}", 200);

        mvc.perform(get(URL).header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].enabled").value(false))
                .andExpect(jsonPath("$.categories[3].enabled").value(false))
                .andExpect(jsonPath("$.categories[1].enabled").value(true));
        assertThat(rows(u.getId())).isEqualTo(2);

        patchBody(u.getId(), "{\"categories\":{\"LIKE\":true}}", 200);
        assertThat(rows(u.getId())).isEqualTo(1);
        patchBody(u.getId(), "{\"categories\":{\"FOLLOWER\":false}}", 200);
        assertThat(rows(u.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("PRD AC3：关「订单与账号」→ 422 push-category-locked，同单的 LIKE 不写")
    void orderAccountLocked() throws Exception {
        User u = newUser();
        mvc.perform(patch(URL).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":{\"ORDER_ACCOUNT\":false,\"LIKE\":false}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/push-category-locked"));
        assertThat(rows(u.getId())).isZero();
    }

    @Test
    @DisplayName("未知分类 code → 400 ProblemDetail")
    void unknownCodeIs400() throws Exception {
        User u = newUser();
        patchBody(u.getId(), "{\"categories\":{\"FOO\":false}}", 400);
        assertThat(rows(u.getId())).isZero();
    }

    @Test
    @DisplayName("兽医 token → 403；无 token → 401")
    void securityRules() throws Exception {
        String vet = vetBearer(vets.newActiveVet("push-settings-it").getId());
        mvc.perform(get(URL).header("Authorization", vet)).andExpect(status().isForbidden());
        mvc.perform(patch(URL).header("Authorization", vet)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"categories\":{\"LIKE\":false}}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("同一用户 1 分钟内第 31 次 PATCH → 429")
    void patchRateLimited() throws Exception {
        User u = newUser();
        redis.delete("rl:push-settings:" + u.getId());
        for (int i = 0; i < 30; i++) {
            patchBody(u.getId(), "{\"categories\":{\"LIKE\":" + (i % 2 == 0) + "}}", 200);
        }
        patchBody(u.getId(), "{\"categories\":{\"LIKE\":false}}", 429);
    }

    @Test
    @DisplayName("CHECK 排除 ORDER_ACCOUNT：库里不可能出现「订单与账号已关闭」")
    void checkRejectsOrderAccount() {
        User u = newUser();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_push_mutes (user_id, category) VALUES (?, 'ORDER_ACCOUNT')", u.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("mutedAmong / isEnabled 读库")
    void serviceReadsDb() throws Exception {
        User u1 = newUser();
        User u2 = newUser();
        User u3 = newUser();
        patchBody(u2.getId(), "{\"categories\":{\"PROMO\":false}}", 200);
        assertThat(prefs.mutedAmong(List.of(u1.getId(), u2.getId(), u3.getId()), PushCategory.PROMO))
                .containsExactly(u2.getId());
        assertThat(prefs.isEnabled(u2.getId(), NotificationType.OPS_ANNOUNCEMENT)).isFalse();
        assertThat(prefs.isEnabled(u2.getId(), NotificationType.TICKET_RESOLVED)).isTrue();
        assertThat(prefs.isEnabled(u1.getId(), NotificationType.OPS_ANNOUNCEMENT)).isTrue();
    }

    @Test
    @DisplayName("AC7：关过两项的用户注销 → user_push_mutes 无该用户行")
    void accountDeletionPurgesMutes() throws Exception {
        User u = newUser();
        patchBody(u.getId(), "{\"categories\":{\"LIKE\":false,\"GIFT\":false}}", 200);
        assertThat(rows(u.getId())).isEqualTo(2);
        AccountDeletion d = deletions.save(AccountDeletion.request(u.getId()));
        accountDeletion.execute(d.getId());
        assertThat(rows(u.getId())).isZero();
        // 残留 access token 不得把刚清掉的行写回来
        patchBody(u.getId(), "{\"categories\":{\"LIKE\":false}}", 404);
        assertThat(rows(u.getId())).isZero();
    }
}

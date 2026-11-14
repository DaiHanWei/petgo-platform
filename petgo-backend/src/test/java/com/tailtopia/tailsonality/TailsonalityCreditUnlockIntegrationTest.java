package com.tailtopia.tailsonality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.entitlement.PackCreditTestSupport;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.tailsonality.domain.TailsonalityCatalog;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-3 · AC4.5 —— 解锁后 {@code unlocked_at} 非空、首次自动佩戴、
 * {@code keepsake_purchases} 无新行；已解锁不扣；无次数 409。
 */
class TailsonalityCreditUnlockIntegrationTest extends ApiIntegrationTest {

    private static final String BASE = "/api/v1/pet-profiles/me/tailsonality/results";

    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private EntitlementCreditService credits;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private User userWithPet() {
        User u = newUser();
        petProfiles.save(PetProfile.create(u.getId(), PetType.CAT, "Momo", null, null, null, null,
                "TOK-" + SEQ.incrementAndGet()));
        return u;
    }

    private String submit(User u) throws Exception {
        String body = TailsonalityCatalog.QUESTION_IDS.stream().map(q -> "\"" + q + "\":1")
                .collect(Collectors.joining(",", "{", "}"));
        String res = mvc.perform(post(BASE).header("Authorization", userBearer(u.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("token").asText();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void unlocksAutoEquipsWithoutKeepsakeRowAndOnlyOnce() throws Exception {
        User u = userWithPet();
        String token = submit(u);
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.TAILSONALITY, 2, "ts-" + u.getId());
        mvc.perform(post(BASE + "/" + token + "/credit-unlock").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.remaining").value(1));
        assertThat(count("SELECT count(*) FROM tailsonality_results WHERE public_token = ? AND unlocked_at IS NOT NULL",
                token)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tailsonality_badges b JOIN tailsonality_results r ON r.id = b.result_id"
                + " WHERE r.public_token = ?", token)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM keepsake_purchases WHERE user_id = ?", u.getId())).isZero();
        mvc.perform(post(BASE + "/" + token + "/credit-unlock").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isOk());
        assertThat(count("SELECT COALESCE(sum(used), 0) FROM entitlement_credits WHERE user_id = ?", u.getId()))
                .isEqualTo(1);
    }

    @Test
    void noCreditIs409() throws Exception {
        User u = userWithPet();
        String token = submit(u);
        mvc.perform(post(BASE + "/" + token + "/credit-unlock").header("Authorization", userBearer(u.getId())))
                .andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM tailsonality_results WHERE public_token = ? AND unlocked_at IS NOT NULL",
                token)).isZero();
    }
}

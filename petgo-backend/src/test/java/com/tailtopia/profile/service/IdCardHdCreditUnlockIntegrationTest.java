package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.auth.domain.User;
import com.tailtopia.entitlement.PackCreditTestSupport;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.profile.repository.IdCardRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 postgres + redis，云端不跑）：V1.5.0 gift-pack Story 4-3 · AC3.6 —— KTP 款扣该款批；该款用完扣任意款；已解锁不扣；
 * 无次数 409 且卡仍未解锁；并发两次只扣一次。
 */
class IdCardHdCreditUnlockIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private EntitlementCreditService credits;
    @Autowired
    private IdCardRepository idCards;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    private long createCard(long userId) throws Exception {
        String body = mvc.perform(post("/api/v1/pet-profiles/me/id-cards")
                        .header(HttpHeaders.AUTHORIZATION, userBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mochi","petType":"CAT","breed":"British","birthday":"2022-01-01",
                                 "gender":"FEMALE","cardType":"KTP"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private ResultActions unlock(long userId, long cardId) throws Exception {
        return mvc.perform(post("/api/v1/pet-profiles/me/id-cards/" + cardId + "/hd-credit-unlock")
                .header(HttpHeaders.AUTHORIZATION, userBearer(userId)));
    }

    private long used(long userId) {
        Long n = jdbc.queryForObject("SELECT COALESCE(sum(used), 0) FROM entitlement_credits WHERE user_id = ?",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    @Test
    void consumesOwnTemplateThenAnyAndSkipsWhenUnlocked() throws Exception {
        User u = newUser();
        long card = createCard(u.getId());
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.KTP_HD_KTP, 1, "ktp-" + u.getId());
        unlock(u.getId(), card).andExpect(status().isOk()).andExpect(jsonPath("$.unlocked").value(true))
                .andExpect(jsonPath("$.remaining").value(0));
        assertThat(idCards.findById(card).orElseThrow().isHdUnlocked()).isTrue();
        assertThat(jdbc.queryForObject("SELECT pay_channel FROM id_card_hd_purchases WHERE card_id = ?", String.class,
                card)).isEqualTo("PACK_CREDIT");
        unlock(u.getId(), card).andExpect(status().isOk());
        assertThat(used(u.getId())).isEqualTo(1);
    }

    @Test
    void noCreditIs409AndCardStaysLocked() throws Exception {
        User u = newUser();
        long card = createCard(u.getId());
        unlock(u.getId(), card).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("entitlement-insufficient")));
        assertThat(idCards.findById(card).orElseThrow().isHdUnlocked()).isFalse();
    }

    @Test
    void otherUsersCardIs404() throws Exception {
        User owner = newUser();
        long card = createCard(owner.getId());
        unlock(newUser().getId(), card).andExpect(status().isNotFound());
    }

    @Test
    void concurrentUnlocksConsumeOnce() throws Exception {
        User u = newUser();
        long card = createCard(u.getId());
        PackCreditTestSupport.grant(tx, credits, u.getId(), CreditType.KTP_HD_KTP, 2, "ktp2-" + u.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> c = () -> unlock(u.getId(), card).andReturn().getResponse().getStatus();
            var a = pool.submit(c);
            var b = pool.submit(c);
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(used(u.getId())).isEqualTo(1);
    }
}

package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.auth.domain.User;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（V1.3.4 ktp Story 2.2，AD-3）：编号归属表——同一用户可共用、跨用户撞号回滚；发号查重改查归属表。
 * 需本分支专用库（云端不跑）。
 */
class CardNumberOwnershipIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CardNumberService cardNumbers;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private static String uniqueNo(String prefix) {
        return prefix + String.format("%07d", SEQ.incrementAndGet() % 10_000_000);
    }

    private List<Map<String, Object>> owners(String number) {
        return jdbc.queryForList("SELECT kind, user_id FROM id_card_number_owners WHERE number = ?", number);
    }

    @Test
    void unclaimedNumbersAreRegisteredToCaller() {
        User a = newUser();
        String c = uniqueNo("TT9903");
        String p = uniqueNo("TT02P");
        tx.executeWithoutResult(s -> cardNumbers.claimNumbers(a.getId(), c, p));
        assertThat(owners(c)).singleElement().satisfies(r -> {
            assertThat(r.get("kind").toString().trim()).isEqualTo("C");
            assertThat(((Number) r.get("user_id")).longValue()).isEqualTo(a.getId());
        });
        assertThat(owners(p)).singleElement().satisfies(r -> assertThat(r.get("kind").toString().trim()).isEqualTo("P"));
    }

    @Test
    void reclaimingOwnNumberPassesAndKeepsOneRow() {
        User a = newUser();
        String c = uniqueNo("TT9903");
        String p = uniqueNo("TT02P");
        tx.executeWithoutResult(s -> cardNumbers.claimNumbers(a.getId(), c, p));
        tx.executeWithoutResult(s -> cardNumbers.claimNumbers(a.getId(), c, p));
        assertThat(owners(c)).hasSize(1);
        assertThat(owners(p)).hasSize(1);
    }

    @Test
    void numberOwnedByAnotherUserThrowsAndRollsBack() {
        User a = newUser();
        User b = newUser();
        String c = uniqueNo("TT9903");
        String p = uniqueNo("TT02P");
        jdbc.update("INSERT INTO id_card_number_owners (kind, number, user_id) VALUES ('C', ?, ?)", c, b.getId());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> cardNumbers.claimNumbers(a.getId(), c, p)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owners(c)).singleElement()
                .satisfies(r -> assertThat(((Number) r.get("user_id")).longValue()).isEqualTo(b.getId()));
        assertThat(owners(p)).as("整笔回滚：护照号也没登记").isEmpty();
    }

    @Test
    void numberOnlyInOwnersTableCountsAsTakenForAllocation() {
        // 预先把「今天第一个可发」的身份码登记给别人（id_cards 里没有这张卡）→ 发号跳过它取下一个序号。
        User b = newUser();
        LocalDate birthday = LocalDate.of(2001, 1, 1); // 罕见生日段，避免与其它用例同日同物种冲突
        String first = tx.execute(s -> cardNumbers.allocateCardNo(birthday, "MALE", "OTHER"));
        int seq = Integer.parseInt(first.substring(10));
        String next = CardNumberService.composeCardNo(birthday, "MALE", "00", seq + 1);
        jdbc.update("INSERT INTO id_card_number_owners (kind, number, user_id) VALUES ('C', ?, ?) ON CONFLICT DO NOTHING",
                next, b.getId());
        String allocated = tx.execute(s -> cardNumbers.allocateCardNo(birthday, "MALE", "OTHER"));
        assertThat(allocated).isNotEqualTo(next);
        assertThat(Integer.parseInt(allocated.substring(10))).isGreaterThan(seq + 1);
    }

    @Test
    void createCardRegistersBothNumbersToOwner() throws Exception {
        // 经建卡接口建的卡：两个号都登记在归属表、归属为本人（与迁移回填后存量卡的形态一致）。
        User a = newUser();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/pet-profiles/me/id-cards")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, userBearer(a.getId()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Momo","petType":"CAT","birthday":"2024-03-10","gender":"FEMALE",
                                 "cardType":"SIM"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        for (var e : Map.of("C", node.get("cardNo").asString(), "P", node.get("passportNo").asString()).entrySet()) {
            assertThat(owners(e.getValue())).as(e.getKey()).singleElement().satisfies(r -> {
                assertThat(r.get("kind").toString().trim()).isEqualTo(e.getKey());
                assertThat(((Number) r.get("user_id")).longValue()).isEqualTo(a.getId());
            });
        }
        // 全库：已登记的号，其归属必与持有该号的经登记卡一致（不存在「登记给 A、卡在 B」）。
        Long mismatched = jdbc.queryForObject("""
                SELECT count(*) FROM id_cards c
                  JOIN id_card_number_owners o
                    ON (o.kind = 'C' AND o.number = c.card_no) OR (o.kind = 'P' AND o.number = c.passport_no)
                 WHERE o.user_id <> c.user_id
                """, Long.class);
        assertThat(mismatched).isZero();
    }
}
